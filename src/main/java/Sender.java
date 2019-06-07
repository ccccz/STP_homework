import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;

enum SenderState {
    /**
     * 关闭连接
     */
    CLOSED,
    /**
     * 正在建立连接
     */
    SYN_SENT,
    /**
     * 建立连接
     */
    ESTABLISHED,
}

/**
 * @author DW
 * @date 2019/5/21
 */
@Data
public class Sender {
    private final int disPort;
    private final double pDrop;
    private final int seedDrop;
    private final int maxDelay;
    private final double pDelay;
    private final int seedDelay;
    private final int mss;
    private final int mws;
    @NonNull
    private String filePath;
    @NonNull
    private String disIP;
    private int initalTimeout;

    private static final Logger logger = LoggerFactory.getLogger(Sender.class);
    private SenderState senderState = SenderState.CLOSED;
    private final String lock = "";
    /**
     * 延迟随机数发生器
     */
    private Random randomDelay;
    /**
     * 丢包随机数发生器
     */
    private Random randomDrop;
    private DatagramSocket datagramSocket;

    /**
     * 连接线程
     */
    private Connect connect;
    /**
     * 接受线程
     */
    private Accept accept;
    /**
     * 发送数据线程
     */
    private Thread transfer;
    /**
     * 读文件线程
     */
    private ReadFile readFile;
    /**
     * 清除窗口已经确认的块列表
     */
    private CleanList cleanList;

    /**
     * 请求连接报文中的seq值,一次使用
     */
    private static int seqNum = 100000;
    /**
     * 文件所含有的字节数
     */
    private int fileLength;
    /**
     * 文件块
     */
    private HashMap<Integer, byte[]> fileParts = new HashMap<>();
    /**
     * 窗口已经确认的块
     */
    private volatile ArrayList<Integer> hasConfirmed = new ArrayList<>();
    /**
     * 窗口
     */
    private HashMap<Integer, byte[]> partSendWindow = new HashMap<>();
    /**
     * 最后一个数据包的分段大小
     */
    private volatile int part = -1;

    /**
     * 滑动窗口的左侧
     */
    private int left;
    /**
     * 目前已经发送成功的字节的数量，正常应该在窗口之内
     */
    private volatile int byteHasSent;
    /**
     * 滑动窗口的右侧
     */
    private int right;

    /**
     * 接受消息是单线程祈祷他不会出错吧。
     */
    private byte[] acceptBuffer;
    /**
     * 接受消息是单线程祈祷他不会出错吧。
     */
    private int toSendSequence;
    /**
     * 接受消息是单线程祈祷他不会出错吧。
     */
    private int toSendAcknolegment;
    /**
     * 接受消息是单线程祈祷他不会出错吧。
     */
    private DatagramPacket inDatagramPacket;

    /**
     * 是否需要快速重传
     */
    private boolean isNeedRetransmit;

    private static final int ARGS = 11;

    private Sender(@NonNull String filePath, @NonNull String disIP, int disPort, double pDrop, int seedDrop, int maxDelay, double pDelay, int seedDelay, int mss, int mws, int initalTimeout) {
        this.filePath = filePath;
        this.disIP = disIP;
        this.disPort = disPort;
        this.pDrop = pDrop;
        this.seedDrop = seedDrop;
        this.maxDelay = maxDelay;
        this.pDelay = pDelay;
        this.seedDelay = seedDelay;
        this.mss = mss;
        this.mws = mws;
        this.initalTimeout = initalTimeout;
        this.randomDelay = new Random((long) seedDelay);
        this.randomDrop = new Random((long) seedDrop);
        this.accept = new Accept();
        this.connect = new Connect();
        readFile = new ReadFile();
        try {
            datagramSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
            logger.error("初始化:socket故障");
            System.exit(-1);
        }
    }

    public static void main(String[] args) {
        if (args.length != ARGS) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

        Sender sender = new Sender(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]),
                Integer.parseInt(args[9]), Integer.parseInt(args[10]));
        System.out.println(sender);
        sender.readFile.start();
        sender.accept.start();
        sender.connect.start();
    }

    /**
     * 发送消息
     */
    private void sendMessage(Message msg) {
        if (msg.getContentLength() < mss && msg.getContentLength() != 0) {
            part = msg.getContentLength();
            logger.warn("即将结束：文件末尾出现:{},目前窗口:left:{},byteHasSent:{},right:{}---当前包序号：{}", part, left, byteHasSent, right, msg.getSequence());
        }

        boolean isDrop = false;
        // data packet才丢包
        if (msg.getContentLength() != 0) {
            isDrop = getDrop();
        }

        if (!isDrop) {
            byte[] pack = msg.enMessage();
            try {
                DatagramPacket sendPac = new DatagramPacket(pack, pack.length,
                        new InetSocketAddress(disIP, disPort));
                int delay = getDelay();
                msg.setTime(Calendar.getInstance().getTimeInMillis());
                Thread.sleep(delay);
                datagramSocket.send(sendPac);
                logger.debug("发送：已经发送sequence：{}，本次延时{}", msg.getSequence(), delay);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                logger.error("发送:发送数据包出现故障，sequence：{}", msg.getSequence());
                System.exit(-1);
            }
        } else {
            logger.debug("发送：sequence丢包：{}", msg.getSequence());
        }
    }

    /**
     * 接受消息
     */
    private Message receiveMessage() {
        byte[] buffer = new byte[1024];
        inDatagramPacket = new DatagramPacket(buffer, buffer.length);
        try {
            datagramSocket.receive(inDatagramPacket);
            acceptBuffer = inDatagramPacket.getData();
            return Message.deMessage(acceptBuffer);
        } catch (IOException e) {
            e.printStackTrace();
            logger.error("接受:接受数据异常，程序退出");
            System.exit(-1);
        }
        return new Message();
    }

    /**
     * 改变接收方状态
     */
    private void changeState(SenderState s) {
        this.senderState = s;
        logger.info("客户端状态改变为{}", s);
    }

    /**
     * @return 延迟时间
     */
    private int getDelay() {
        int time = (int) ((pDelay <= 0) ? 0 : max(0, randomDelay.nextDouble() - 1.0 + pDelay) / pDelay * maxDelay);
        return time;
    }

    /**
     * @return true：丢包，false：不丢包
     */
    private boolean getDrop() {
        boolean b = (pDrop > 0) && randomDrop.nextDouble() < pDrop;
        return b;
    }

    /**
     * 用于建立连接。
     */
    class Connect extends Thread {
        private final String locks = "";

        void reRun() {
            synchronized (locks) {
                locks.notifyAll();
            }
        }

        /**
         * ACK = 0
         * SYN = 1
         * sequence = x
         * acknolegment = 0
         */
        private Message setSYNMessage(int seqNum) {
            Message msg = new Message();
            msg.setACK(false);
            msg.setSYN(true);
            msg.setSequence(seqNum);
            msg.setAcknolegment(0);
            msg.setContent(new byte[]{});
            return msg;
        }

        /**
         * ACK = 1
         * SYN = 0
         * sequence = x+1
         * acknolegment = y+1
         */
        private Message setACKMessage() {
            Message message = new Message();
            message.setACK(true);
            message.setSYN(true);
            message.setSequence(toSendSequence);
            message.setAcknolegment(toSendAcknolegment);
            message.setContent(new byte[]{});
            return message;
        }

        @Override
        public void run() {
            logger.info("Connect run()!");
            // 只要连接还没有建立，就一直尝试建立连接
            while (senderState != SenderState.ESTABLISHED) {
                // 发送"连接请求报文"，即SYN packet
                sendMessage(setSYNMessage(seqNum++));
                logger.debug("发送: 发送了SYN.");
                if (senderState != SenderState.SYN_SENT) {
                    changeState(SenderState.SYN_SENT);
                }
                try {
                    synchronized (locks) {
                        lock.wait(initalTimeout);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            sendMessage(setACKMessage());
            logger.debug("发送: 发送了ACK.");
            // 连接已经建立，可以传输文件了
            transfer = new Transfer();
            transfer.start();
            cleanList = new CleanList();
            cleanList.setDaemon(true);
            cleanList.start();
        }
    }

    /**
     * 用于接收
     */
    class Accept extends Thread {
        @Override
        public void run() {
            logger.debug("Accept run()!");

            while (true) {
                Message response = receiveMessage();
                // 如果Sender接收到的packet是SYN ACK packet，那么就改变Sender状态为SYN_ACK
                if (response.isSYNACK() && senderState == SenderState.SYN_SENT) {
                    logger.debug("Sender: receive SYN ACK.");
                    toSendSequence = response.getAcknolegment();
                    toSendAcknolegment = response.getSequence() + 1;
                    changeState(SenderState.ESTABLISHED);
                    connect.reRun();
                } else if (response.isACK() && senderState == SenderState.ESTABLISHED) {
                    // 收到的包是数据包
                    int ackReply = response.getAcknolegment();
                    logger.info("接受：收到确认包:{}", ackReply);

                    if ((part > 0 && (left + part == ackReply))
                            || left + mss < ackReply) {
                        synchronized (lock) {
                            if (!hasConfirmed.contains(ackReply)) {
                                hasConfirmed.add(ackReply);
                            }
                        }
                    } else if (left + mss == ackReply) {
                        synchronized (lock) {
                            left = left + mss;
                            if (fileLength >= right + mss) {
                                right = right + mss;
                            } else {
                                right = fileLength;
                            }
                            while (hasConfirmed.contains(left + mss)) {
                                left += mss;
                                logger.debug("左边更新left:{}，right:{}", left, right);
                                if (fileLength >= right + mss) {
                                    right = right + mss;
                                } else {
                                    right = fileLength;
                                }
                                logger.debug("右边更新left:{}，right:{}", left, right);
                            }
                        }
                    } else if (left - part != ackReply && left != ackReply) {
                        logger.error("接受：回复包出现问题：{}---{}---{}===ackReply：{}", left, byteHasSent, right, ackReply);
                        logger.error("====?????====");
                    }

                    synchronized (lock) {
                        if (part != -1 && hasConfirmed.contains(left + part)) {
                            left += part;
                            logger.debug("左边更新{}---{}", left, right);
                            right = fileLength;
                            logger.debug("右边更新{}---{}", left, right);
                            partSendWindow.remove(ackReply - part);
                            part = -1;
                        } else if (ackReply == fileLength) {
                            partSendWindow.remove(ackReply - part);
                        } else {
                            partSendWindow.remove(ackReply - mss);
                        }
                    }
                    logger.debug("接受：目前窗口情况：{}---{}---{}", left, byteHasSent, right);

                } else if (response.isFIN()) {
                    //收到包是终止包
                    logger.debug("接受： receive FIN.");
                    changeState(SenderState.CLOSED);
                    datagramSocket.close();
                    System.exit(0);
                }
            }
        }
    }

    /**
     * 用于传输：把位于滑动窗口中的数据发送出去。当文件传输完毕后，发送FIN packet，关闭连接。
     */
    class Transfer extends Thread {
        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(mss + 2,
                new BasicThreadFactory.Builder().namingPattern("resend-pool-%d").daemon(false).build());

        private int wd = mws / mss;
        private Map<Integer, ScheduledFuture> threadPools = new HashMap<>();


        Transfer() {
            // 初始化滑动窗口的左右两边
            left = 0;
            right = mws > fileLength ? fileLength : mws;
            logger.debug("发送：初始化滑动窗口，{}---{},mws:{}", left, right, mws);
            toSendSequence = 0;
        }

        private Message setDataMessage(int sequence, byte[] bytes) {
            Message message = new Message();
            message.setACK(false);
            message.setSYN(false);
            message.setFIN(false);
            message.setRST(false);
            message.setSequence(sequence);
            //setCRC含在其中了
            message.setContent(bytes);
            message.setTime((new Date()).getTime());
            return message;
        }

        private void sendMessageBySequence(int sequence) {
            logger.warn("发送队列增加,{}", sequence);
            partSendWindow.put(sequence, fileParts.get(sequence));
            byteHasSent += fileParts.get(sequence).length;
            ResendThread r = new ResendThread(sequence);
            ScheduledFuture<?> future = executorService.scheduleAtFixedRate(r, 0, initalTimeout, TimeUnit.MILLISECONDS);
            threadPools.put(sequence, future);
        }

        class ResendThread implements Runnable {
            private int x;
            private int n = 0;

            ResendThread(int x) {
                this.x = x;
            }

            @Override
            public void run() {
                if (partSendWindow.containsKey(x) && left < right) {
                    Transfer.this.transmit(x);
                    logger.warn("{}重传次数：{}", x, n++);
                } else {
                    ScheduledFuture<?> future = threadPools.remove(x);
                    future.cancel(true);
                    logger.warn("发送队列去除,{}", x);
                }
            }
        }

        private void transmit(int sequence) {
            sendMessage(setDataMessage(sequence, partSendWindow.get(sequence)));
        }

        private Message setFINMessage() {

            Message message = new Message();
            message.setFIN(true);
            message.setSequence(toSendSequence);
            message.setContent(new byte[]{});
            message.setTime((new Date()).getTime());
            logger.warn("fin包已经准备！！");
            return message;
        }

        @Override
        public void run() {
            logger.info("Transfer run()!");

            while (senderState == SenderState.ESTABLISHED) {
                // 如果文件发送完了，就发送FIN packet
                if (left == right) {
                    sendMessage(setFINMessage());
                    logger.error("========发送完毕！！！=========");
                }
                synchronized (lock) {
                    while (left < right && byteHasSent < right && partSendWindow.size() < wd) {
                        sendMessageBySequence(byteHasSent);
                        logger.info("发送：窗口：{}-{}-{}", left, byteHasSent, right);
                    }
                }
            }
        }
    }

    /**
     * 用于切片文件
     */
    @AllArgsConstructor
    class ReadFile extends Thread {
        @Override
        public void run() {
            try (BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(filePath))) {
                fileLength = bufferedInputStream.available();
                int hasRead = 0;
                byte[] b = new byte[mss];
                int len;
                while ((len = bufferedInputStream.read(b, 0, mss)) != -1) {
                    fileParts.put(hasRead, Arrays.copyOf(b, len));
                    hasRead += mss;
                    b = new byte[mss];
                }
                logger.info("文件读取完毕：{}", fileLength);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 清除窗口已经确认的块列表
     */
    class CleanList extends Thread {
        @Override
        public void run() {
            while (senderState != SenderState.CLOSED) {
                synchronized (lock) {
                    if (hasConfirmed.size() != 0) {
                        hasConfirmed.removeIf(item -> item < left);
                    }
                }
            }
        }
    }
}