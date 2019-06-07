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
    //客户端状态
    CLOSED,

    //建立连接
    SYN_SENT,
    //    SYN_ACK_RECEIVED,  // Sender是否收到了SYN ACK
    ESTABLISHED,

    //放弃连接
    FIN_WAIT_1,
    FIN_WAIT_2,
    TIME_WAIT
}

/**
 * @author DW
 * @date 2019/5/21
 */
@Data
public class Sender {
    private static final Logger logger = LoggerFactory.getLogger(Sender.class);
    private static int seqNum = 100000;  // 请求连接报文中的seq值
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
    @NonNull
    private int initalTimeout;
    /**
     * 文件所含有的字节数
     */
    private int fileLength;
    private SenderState senderState = SenderState.CLOSED;
    /**
     * 目前已经发送成功的字节的数量，不包含本数据包
     */
    private volatile int byteHasSent;
    /**
     * 需要的下一字节
     */
    private int byteHasAcked;
    /**
     * 滑动窗口的左侧
     */
    private int left;
    /**
     * 滑动窗口的右侧
     */
    private int right;
    private BufferedInputStream bufferedInputStream;
    /**
     * 存储已经发送的，但还没有收到确认的数据
     */
    private HashMap<Integer, byte[]> hasSentButNotAcked = new HashMap<>();
    /**
     * 延迟随机数发生器
     */
    private Random randomDelay;
    /**
     * 丢包随机数发生器
     */
    private Random randomDrop;
    private Message toSendMessage;
    private Message receivedMessage;
    private byte[] toSendPacket;
    private byte[] acceptBuffer;
    private int toSendSequence;
    private int toSendAcknolegment;
    private DatagramSocket datagramSocket;
    private DatagramPacket inDatagramPacket;
    private DatagramPacket outDatagramPacket;
    private boolean isNeedRetransmit;  // 是否需要快速重传
    private int retransmitSequence;  // 需要快速重传的数据的第一个字节号
    private Accept accept;
    private Thread transfer;
    private Connect connect;
    private HashMap<Integer, byte[]> fileParts = new HashMap<>();
    private ReadFile readFile;
    private HashMap<Integer, byte[]> partSendWindow = new HashMap<>();
    private volatile ArrayList<Integer> hasConfirmed = new ArrayList<>();
    private final String lock = "";
    private int part = -1;


    private Sender(@NonNull String filePath, @NonNull String disIP, int disPort, double pDrop, int seedDrop, int maxDelay, double pDelay, int seedDelay, int mss, int mws, @NonNull int initalTimeout) {
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
        }
    }

    public static void main(String[] args) {
        if (args.length != 11) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

        Sender sender = new Sender(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]),
                Integer.parseInt(args[9]), Integer.parseInt(args[10]));

        sender.accept.start();
        sender.connect.start();
        new Thread(sender.readFile).start();
    }

    /**
     * 发送消息
     */
    private void sendMessage(Message msg) {
        if (msg.getContentLength() < mss) {
            part = msg.getContentLength();
        }
        // 如果getDrop()函数返回true，就丢包
        boolean isDrop = false;
        // 如果该packet是data packet才有可能丢包
        if (msg.getContentLength() != 0) {
            isDrop = getDrop();
        }
        if (!isDrop) {
            // 获取要发送的packet
            toSendPacket = msg.enMessage();
            try {
                outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length,
                        new InetSocketAddress(disIP, disPort));
                int delay = getDelay();
                msg.setTime(Calendar.getInstance().getTimeInMillis());
                Thread.sleep(delay);
                datagramSocket.send(outDatagramPacket);
                logger.info("发送：已经发送sequence：{},时间:{}", msg.getSequence(), msg.getTime());
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            logger.debug("发送：sequence丢包：{}", msg.getSequence());
        }
    }

    /**
     * 接受消息
     */
    private void receiveMessage() {
        byte[] buffer = new byte[1024];
        inDatagramPacket = new DatagramPacket(buffer, buffer.length);
        try {
            datagramSocket.receive(inDatagramPacket);
            acceptBuffer = inDatagramPacket.getData();
            receivedMessage = Message.deMessage(acceptBuffer);
        } catch (IOException e) {
            e.printStackTrace();
        }
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
        logger.debug("本次延迟时间{}", time);
        return time;
    }

    /**
     * @return true：丢包，false：不丢包
     */
    private boolean getDrop() {
        boolean b = (pDrop > 0) && randomDrop.nextDouble() < pDrop;
        logger.debug("本次丢包情况:{}", b ? "丢包" : "不丢包");
        return b;
    }

    /**
     * 用于建立连接。
     */
    class Connect extends Thread {
        private final String lock = "";

        void reRun() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        /**
         * ACK = 0
         * SYN = 1
         * sequence = x
         * acknolegment = 0
         */
        private void setSYNMessage(int seqNum) {
            toSendMessage = new Message();
            toSendMessage.setACK(false);
            toSendMessage.setSYN(true);
            toSendMessage.setSequence(seqNum);  // 这个随便取
            toSendMessage.setAcknolegment(0);
        }

        /**
         * ACK = 1
         * SYN = 0
         * sequence = x+1
         * acknolegment = y+1
         */
        private void setACKMessage() {
            toSendMessage = new Message();
            toSendMessage.setACK(true);
            toSendMessage.setSYN(false);
            toSendMessage.setSequence(toSendSequence);
            toSendMessage.setAcknolegment(toSendAcknolegment);
            toSendMessage.setContent(new byte[]{});
        }

        @Override
        public void run() {
            logger.debug("Connect run()!");
            // 只要连接还没有建立，就一直尝试建立连接
            while (senderState != SenderState.ESTABLISHED) {
                // 发送"连接请求报文"，即SYN packet
                setSYNMessage(seqNum++);
                sendMessage(toSendMessage);
                logger.debug("Sender: send SYN.");
                if (senderState != SenderState.SYN_SENT) {
                    changeState(SenderState.SYN_SENT);
                }
                try {
                    synchronized (lock) {
                        lock.wait(initalTimeout);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            setACKMessage();
            sendMessage(toSendMessage);
            logger.debug("Sender: send ACK.");
            // 连接已经建立，可以传输文件了
            transfer = new Transfer();
            transfer.start();
            new CleanList().start();
        }

    }

    /**
     * 用于接收
     */
    class Accept extends Thread {
        @Override
        public void run() {
            logger.debug("Accept run()!");

            // 记录重复收到的ACK数量
            int duplicateACK = 1;

            while (true) {
                receiveMessage();
                // 如果Sender接收到的packet是SYN ACK packet，那么就改变Sender状态为SYN_ACK
                if (receivedMessage.isSYNACK() && senderState == SenderState.SYN_SENT && receivedMessage.getAcknolegment() == seqNum) {
                    logger.debug("Sender: receive SYN ACK.");
                    toSendSequence = receivedMessage.getAcknolegment();
                    toSendAcknolegment = receivedMessage.getSequence() + 1;
                    changeState(SenderState.ESTABLISHED);
                    connect.reRun();
                } else if (receivedMessage.isACK() && senderState == SenderState.ESTABLISHED) {
                    // 收到的包是数据包
                    //确认包
                    int ackReply = receivedMessage.getAcknolegment();

                    if (left + mss < ackReply) {
                        synchronized (lock) {
                            if (!hasConfirmed.contains(ackReply)) {
                                hasConfirmed.add(ackReply);
                            }
                        }

                    } else if (left + mss == ackReply) {
                        left = ackReply;
                        synchronized (lock) {
                            while (hasConfirmed.contains(left + mss)) {
                                left += mss;
                                if (fileLength >= left + mws) {
                                    right = left + mws;
                                } else {
                                    right = left + fileLength - left;
                                }
                            }

                            if (hasConfirmed.contains(left + part)) {
                                left += part;
                                if (fileLength >= left + mws) {
                                    right = left + mws;
                                } else {
                                    right = left + fileLength - left;
                                }
                            }
                        }
                    } else {
                        logger.error("?????");
//                        System.exit(-1);
                    }
                    partSendWindow.remove(ackReply - mss);
                    logger.info("接受：收到确认包:{}", ackReply);
                    logger.debug("接受：目前窗口情况：left:{},byteHasSent:{},right:{}", left, byteHasSent, right);

                } else if (receivedMessage.isFIN()) {
                    //收到包是终止包
                    logger.debug("接受： receive FIN.");
                    changeState(SenderState.CLOSED);
                    datagramSocket.close();
                    if (bufferedInputStream != null) {
                        try {
                            bufferedInputStream.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
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
            logger.debug("发送：初始化滑动窗口，left：{}，right：{}，mws：{}", left, right, mws);
            toSendSequence = 0;
            byteHasAcked = 0;
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

            ResendThread(int x) {
                this.x = x;
            }

            @Override
            public void run() {
                if (partSendWindow.containsKey(x)) {
                    Transfer.this.transmit(x);
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
            message.setContent(new byte[]{0});
            byte[] toSendCRC = CRC16.generateCRC(new byte[]{0, 0});
            message.setCrc16(toSendCRC);
            message.setTime((new Date()).getTime());
            logger.warn("fin包已经准备！！");
            return message;
        }

        @Override
        public void run() {
            logger.info("Transfer run()!");

            while (senderState == SenderState.ESTABLISHED) {
                // 如果文件发送完了，就发送FIN packet
                if (byteHasAcked == fileLength || left == right) {
                    sendMessage(setFINMessage());
                    logger.error("========发送完毕！！！=========");
                }

                //logger.debug("{}",hasConfirmed);
                while (byteHasSent < right && partSendWindow.size() < wd) {
                    sendMessageBySequence(byteHasSent);
                    logger.info("发送： 窗口：{}-{}-{}", left, byteHasSent, right);
                }
            }
        }
    }

    /**
     * 用于切片文件
     */
    @AllArgsConstructor
    class ReadFile implements Runnable {
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