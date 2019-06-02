import lombok.Data;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * @author DW
 * @date 2019/5/22
 */
@Data
public class Receiver {
    @NonNull
    private String ip;
    @NonNull
    private String filePath;
    private final int port;
    private final double pDrop;
    private final int seedDrop;
    private final int maxDelay;
    private final double pDelay;
    private final int seedDelay;

    private static final Logger logger = LoggerFactory.getLogger(Sender.class);
    private ReceiverState receiverState = ReceiverState.CLOSED;
    private int mss = 0;
    /**
     * 目前已经发送成功的序号
     */
    private int sequence = 115;
    /**
     * 确认号
     */
    private int rcvdSequence;


    /**
     *
     * @param message
     * @return
     */
    DatagramPacket receiveMessage(Message message) {
        try (DatagramSocket socket = new DatagramSocket(this.port)) {
            DatagramPacket request = new DatagramPacket(new byte[mss + Message.HEAD_LENGTH], mss + Message.HEAD_LENGTH);
            socket.receive(request);
            Message recMessage = Message.deMessage(request.getData());

            message.setTime(recMessage.getTime());
            logger.info("接受到报文，长度:{},报文序号{}", request.getLength(), recMessage.getRank());
            //设置确认号
            if (recMessage.isSyn()) {
                message.setSeq(recMessage.getRank() + 1);
            } else {
                int len = recMessage.getContentLength();
                if (len == 0 && recMessage.isFin()) {
                    len = 1;
                }
                message.setSeq(recMessage.getRank() + len);
            }

            return new DatagramPacket(message.enMessage(), message.enMessage().length, request.getAddress(), request.getPort());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * 获取预构造信息
     * TODO: hl  获取预构造好的将发送的信息
     */
    Message getMessage() {
        Message msg = new Message();
        msg.setAck(true);
        msg.setSrcPort(this.port);
        return msg;
    }

    /**
     * 改变接收方状态
     */
    void changeState(ReceiverState s) {
        this.receiverState = s;
        logger.info("收到请求连接数据包，服务端状态改变为{}", s);
    }

    /**
     * 建立连接
     * TODO: yh 有问题++，可以自行设计
     * @param receiver 接受方
     */
    static void establishConnection(Receiver receiver) {
        //第2次握手
        while (true) {
            try (DatagramSocket socket = new DatagramSocket(receiver.port)) {
                //收
                DatagramPacket request = new DatagramPacket(new byte[receiver.mss + Message.HEAD_LENGTH], receiver.mss + Message.HEAD_LENGTH);
                socket.receive(request);

                //验证
                Message recMessage = Message.deMessage(request.getData());
                if (!recMessage.isSyn()) {
                    logger.error("上述报文非同步报文");
                    continue;
                }

                //更新信息
                receiver.setSequence(receiver.getSequence() + 1);
                receiver.setRcvdSequence(recMessage.getRank() + 1);
                receiver.setMss(recMessage.getMss());
                //TODO 这里信息状态貌似不对,有问题
                receiver.changeState(ReceiverState.LISTEN);

                //准备回复
                Message toSent = receiver.getMessage();
                toSent.setRank(receiver.getSequence());
                toSent.setSyn(true);
                toSent.setContent(new byte[0]);
                toSent.setTime(recMessage.getTime());
                toSent.setSeq(recMessage.getRank() + 1);

                //回复
                byte[] messByte = toSent.enMessage();
                DatagramPacket response = new DatagramPacket(messByte, messByte.length, request.getAddress(), request.getPort());
                socket.send(response);
                break;
            } catch (IOException e) {
                e.printStackTrace();
                logger.error("遇到异常，程序意外终止");
                System.exit(-1);
            }
        }

        //第3次握手的确认
        while (true) {
            try (DatagramSocket socket = new DatagramSocket(receiver.port)) {
                //收
                DatagramPacket request = new DatagramPacket(new byte[receiver.mss + Message.HEAD_LENGTH], receiver.mss + Message.HEAD_LENGTH);
                socket.receive(request);

                //验证
                Message recMessage = Message.deMessage(request.getData());
                if (!recMessage.isAck() || recMessage.getRank() != receiver.getRcvdSequence()) {
                    logger.error("上述报文非第三次握手报文");
                    continue;
                }

                //更新信息
                receiver.setSequence(receiver.getSequence() + 1);
                receiver.setRcvdSequence(recMessage.getRank() + recMessage.getContentLength());
                receiver.changeState(ReceiverState.ESTABLISHED);

                socket.send(new DatagramPacket(new byte[0], 0, request.getAddress(), request.getPort()));
                break;
            } catch (IOException e) {
                e.printStackTrace();
                logger.error("遇到异常，程序意外终止");
                System.exit(-1);
            }
        }

        logger.info("三次握手成功！连接建立完成！");
    }

    public static void main(String[] args) {
        if (args.length != 8) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

        Receiver r = new Receiver(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]));

        establishConnection(r);

        //TODO 对非建立连接的消息进行过滤

    }

    //TODO: sn 将获取到的二进制字节流转化为文档
}

enum ReceiverState {
    //客户端状态
    CLOSED,

    //建立连接
    LISTEN,
    SYN_RECEIVED,
    ESTABLISHED,

    //放弃连接
    CLOSE_WAIT,
    LAST_ACK
}