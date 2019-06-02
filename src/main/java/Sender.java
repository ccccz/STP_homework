import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Data;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;


/**
 * @author DW
 * @date 2019/5/21
 */
@Data
public class Sender {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sender.class);

    @NonNull
    private String filePath;  //文件路径
    @NonNull
    private String disIP;   //接收方程序的IP地址
    private final int disPort;    //接收方程序的端口
    private final double pDrop;    //丢包概率
    private final int seedDrop;    //丢包随机数种子
    private final int maxDelay;    //最大延迟
    private final double pDelay;    //延迟率
    private final int seedDelay;    //
    private final int mss;     //最大分段
    private final int mws;     //最大窗口大小
    @NonNull
    private int initalTimeout;     //初始的超时

    private SenderState senderState = SenderState.CLOSED;
    /**
     * 目前已经发送成功的序号，不包含本数据包
     */
    private int sequence = 500;
    /**
     * 确认号
     */
    private int rcvdSequence;
    /**
     * 窗口
     */
    private ArrayList<SendThread> window;
    /**
     * 发送池
     */
    private ThreadPoolExecutor toSend;
    /**
     * 延迟随机数发生器
     */
    private Random randomDelay;
    /**
     * 丢包随机数发生器
     */
    private Random randomDrop;

    public static void main(@NotNull String[] args) {
        if (args.length != 11) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

        Sender s = new Sender(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]),
                Integer.parseInt(args[9]), Integer.parseInt(args[10]));
        s.setRandomDelay(new Random((long) s.seedDelay));
        s.setRandomDrop(new Random((long) s.seedDrop));
        s.window = new ArrayList<>(s.mws);
        ThreadFactory f = new ThreadFactoryBuilder().setNameFormat("发送数据包-%d").build();
        s.toSend = new ThreadPoolExecutor(s.mws, s.mws + 2, s.getInitalTimeout(), TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(s.mws), f);
        establish(s);

    }


    /**
     * 发送消息
     *
     * @param message 准备发送的消息
     * @return 收到的确认消息
     * TODO: yh 这个方法有问题，可以抛弃重写
     */
    private Message sendMessage(@NotNull Message message) {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            socket.setSoTimeout(initalTimeout);

            message.setSrcPort(socket.getPort());
            byte[] messByte = message.enMessage();
            DatagramPacket request = new DatagramPacket(messByte, messByte.length, InetAddress.getByName(disIP), this.disPort);
            socket.send(request);

            DatagramPacket response = new DatagramPacket(new byte[mss + Message.HEAD_LENGTH], mss + Message.HEAD_LENGTH);
            socket.receive(response);

            return Message.deMessage(response.getData());
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.error("遇到异常，程序意外终止");
            System.exit(-1);
        }
        return null;
    }

    /**
     * 改变接收方状态
     */
    private void changeState(SenderState s) {
        this.senderState = s;
        LOGGER.info("客户端状态改变为{}", s);
    }

    /**
     * TODO: hl 获取预构造好的将发送的信息
     */
    private Message getMessage() {
        Message msg = new Message();
        msg.setDisPort(this.disPort);
        msg.setAck(true);
        msg.setTime(Calendar.getInstance().getTimeInMillis());
        return msg;
    }

    /**
     * 建立连接
     * TODO: yh 第一次握手和接收报文都没有问题，但第二次发送报文和之后还没有设计完成
     * TODO yh 羽涵辛苦了，可能需要设计一下收发包的策略
     */
    private static void establish(Sender sender) {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            socket.setSoTimeout(sender.initalTimeout);

            STATE1:
            while (true) {
                //握手1
                //准备报文
                Message m = sender.getMessage();
                m.setAck(false);
                m.setSyn(true);
                m.setRank(sender.getSequence());
                m.setMss(m.getMss());
                m.setContent(new byte[0]);
                m.setSrcPort(socket.getPort());
                byte[] messByte = m.enMessage();
                if (sender.getDrop()) {
                    try {
                        Thread.sleep(sender.maxDelay);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    Sender.LOGGER.info("该数据包丢失，重新发送...");
                    continue;
                }
                //发送报文
                long temp = Calendar.getInstance().getTimeInMillis();
                try {
                    Thread.sleep(sender.getDelay());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Message replyMess;
                DatagramPacket response;
                DatagramPacket request = new DatagramPacket(messByte, messByte.length, InetAddress.getByName(sender.disIP), sender.disPort);
                socket.send(request);

                //接受报文
                while (true) {
                    if (Calendar.getInstance().getTimeInMillis() - temp >= sender.maxDelay) {
                        Sender.LOGGER.info("该数据包超时，重新发送...");
                        continue STATE1;
                    }

                    response = new DatagramPacket(new byte[sender.mss + Message.HEAD_LENGTH], sender.mss + Message.HEAD_LENGTH);
                    socket.receive(response);
                    replyMess = Message.deMessage(response.getData());

                    //验证报文
                    if (!replyMess.isSyn() | !replyMess.isAck() | replyMess.getSeq() != sender.getSequence() + 1) {
                        LOGGER.error("上述报文非同步报文");
                        continue;
                    }
                    break;
                }

                //更新状态
                sender.changeState(SenderState.SYN_SENT);
                sender.setSequence(sender.getSequence() + 1);
                sender.setRcvdSequence(replyMess.getRank() + 1);

                //握手3
                //准备报文
                m = sender.getMessage();
                m.setRank(sender.getSequence());
                m.setContent(new byte[0]);
                m.setSeq(sender.getRcvdSequence());

                //发送报文
                sender.sendMessage(m);

                //更新状态
                sender.changeState(SenderState.ESTABLISHED);

                break;
            }
            LOGGER.info("三次握手成功！连接建立完成！");

        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.error("遇到异常，程序意外终止");
            System.exit(-1);
        }
    }

    /**
     * @return 延迟时间
     */
    private int getDelay() {
        int time = (int) ((pDelay <= 0) ? 0 : max(0, randomDelay.nextDouble() - 1.0 + pDelay) / pDelay * maxDelay);
        LOGGER.debug("本次延迟时间{}", time);
        return time;
    }

    /**
     * @return true：丢包，false：不丢包
     */
    private boolean getDrop() {
        boolean b = (pDrop > 0) && randomDrop.nextDouble() < pDrop;
        LOGGER.debug("本次丢包情况:{}", b ? "丢包" : "不丢包");
        return b;
    }

    private void addSendThread(Sender sender, DatagramSocket socket, byte[] messByte) {
        int times = 0;
        while (times < 4) {
            //丢失直接等待
            if (sender.getDrop()) {
                try {
                    Thread.sleep(sender.maxDelay);
                    times++;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Sender.LOGGER.info("该数据包丢失，重新发送...");
            }
            //延迟
            try {
                Thread.sleep(sender.getDelay());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            sender.window.add(new SendThread(sender, socket, messByte));

        }
        Sender.LOGGER.error("网络环境太差，请重新设置相关参数");
        System.exit(-1);
    }

    private void getResponseThread() throws IOException {

    }

    /**
     * 创建发送线程
     *
     */
    static class SendThread extends Thread {
        private Sender sender;
        private DatagramSocket socket;
        private byte[] messByte;
        private byte[] replyByte;

        SendThread(Sender sender, DatagramSocket socket, byte[] messByte) {
            this.sender = sender;
            this.socket = socket;
            this.messByte = messByte;
            this.replyByte = new byte[sender.mss + Message.HEAD_LENGTH];
        }

        @Override
        public synchronized void run() {
            super.run();
            long temp = Calendar.getInstance().getTimeInMillis();
            long delayTime = sender.getDelay();
            try {
                Thread.sleep(sender.getDelay());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            try {
                DatagramPacket request = new DatagramPacket(messByte, messByte.length, InetAddress.getByName(sender.disIP), sender.disPort);
                socket.send(request);
                sender.getResponseThread();
                if (delayTime < sender.maxDelay) {
                    wait(sender.maxDelay - delayTime);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    //TODO: sn 读取文件并转化为二进制
    //TODO: dyh 发送消息策略
}
enum SenderState {
    //客户端状态
    CLOSED,

    //建立连接
    SYN_SENT,
    ESTABLISHED,

    //放弃连接
    FIN_WAIT_1,
    FIN_WAIT_2,
    TIME_WAIT
}
