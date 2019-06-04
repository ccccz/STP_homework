import lombok.Data;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.HashMap;

/**
 * @author DW
 * @date 2019/5/22
 */
@Data
public class Receiver extends Thread{
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
//    private int sequence = 115;
    /**
     * 确认号
     */
//    private int rcvdSequence;

    private FileOutputStream fileOutputStream;
    private DatagramSocket datagramSocket;
    private DatagramPacket inDatagramPacket;  // 从Sender接收
    private DatagramPacket outDatagramPacket;  // 向Sender发送
    private HashMap<Integer, byte[]> window = new HashMap<>();
    private Message receivedMessage;
    private Message toSendMessage;
    private byte[] toSendPacket;
    private byte[] receiveBuffer;

    /**
     * 已经写入文件的字节号
     */
    private int byteHasWrite=0;
    /**
     * 将要发送给Sender的sequence
     */
    private int toSendSequence=0;
    /**
     * 将要发送给Sender的acknolegment
     */
    private int toSendAcknolegment=0;
    private int lastSendSequence = 0;
    private int lastSendAcknolegment = 0;

    private SocketAddress des_address;


//    /**
//     *
//     * @param message
//     * @return
//     */
//    DatagramPacket receiveMessage(Message message) {
//        try (DatagramSocket socket = new DatagramSocket(this.port)) {
//            DatagramPacket request = new DatagramPacket(new byte[mss + Message.HEAD_LENGTH], mss + Message.HEAD_LENGTH);
//            socket.receive(request);
//            Message recMessage = Message.deMessage(request.getData());
//
//            message.setTime(recMessage.getTime());
//            logger.info("接受到报文，长度:{},报文序号{}", request.getLength(), recMessage.getRank());
//            //设置确认号
//            if (recMessage.isSYN()) {
//                message.setSeq(recMessage.getRank() + 1);
//            } else {
//                int len = recMessage.getContentLength();
//                if (len == 0 && recMessage.isFIN()) {
//                    len = 1;
//                }
//                message.setSeq(recMessage.getRank() + len);
//            }
//
//            return new DatagramPacket(message.enMessage(), message.enMessage().length, request.getAddress(), request.getPort());
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        return null;
//    }
//

//    /**
//     * 获取预构造信息
//     * TODO: hl  获取预构造好的将发送的信息
//     */
//    Message getMessage() {
//        Message msg = new Message();
//        msg.setACK(true);
//        msg.setSrcPort(this.port);
//        return msg;
//    }

    /**
     * 改变接收方状态
     */
    private void changeState(ReceiverState s) {
        this.receiverState = s;
        logger.info("服务端状态改变为{}", s);
    }

//    /**
//     * 建立连接
//     * TODO: yh 有问题++，可以自行设计
//     * @param receiver 接受方
//     */
//    static void establishConnection(Receiver receiver) {
//        //第2次握手
//        while (true) {
//            try (DatagramSocket socket = new DatagramSocket(receiver.port)) {
//                //收
//                DatagramPacket request = new DatagramPacket(new byte[receiver.mss + Message.HEAD_LENGTH], receiver.mss + Message.HEAD_LENGTH);
//                socket.receive(request);
//
//                //验证
//                Message recMessage = Message.deMessage(request.getData());
//                if (!recMessage.isSYN()) {
//                    logger.error("上述报文非同步报文");
//                    continue;
//                }
//
//                //更新信息
//                receiver.setSequence(receiver.getSequence() + 1);
//                receiver.setRcvdSequence(recMessage.getRank() + 1);
//                receiver.setMss(recMessage.getMss());
//                //TODO 这里信息状态貌似不对,有问题
//                receiver.changeState(ReceiverState.LISTEN);
//
//                //准备回复
//                Message toSent = receiver.getMessage();
//                toSent.setRank(receiver.getSequence());
//                toSent.setSYN(true);
//                toSent.setContent(new byte[0]);
//                toSent.setTime(recMessage.getTime());
//                toSent.setSeq(recMessage.getRank() + 1);
//
//                //回复
//                byte[] messByte = toSent.enMessage();
//                DatagramPacket response = new DatagramPacket(messByte, messByte.length, request.getAddress(), request.getPort());
//                socket.send(response);
//                break;
//            } catch (IOException e) {
//                e.printStackTrace();
//                logger.error("遇到异常，程序意外终止");
//                System.exit(-1);
//            }
//        }
//
//        //第3次握手的确认
//        while (true) {
//            try (DatagramSocket socket = new DatagramSocket(receiver.port)) {
//                //收
//                DatagramPacket request = new DatagramPacket(new byte[receiver.mss + Message.HEAD_LENGTH], receiver.mss + Message.HEAD_LENGTH);
//                socket.receive(request);
//
//                //验证
//                Message recMessage = Message.deMessage(request.getData());
//                if (!recMessage.isACK() || recMessage.getRank() != receiver.getRcvdSequence()) {
//                    logger.error("上述报文非第三次握手报文");
//                    continue;
//                }
//
//                //更新信息
//                receiver.setSequence(receiver.getSequence() + 1);
//                receiver.setRcvdSequence(recMessage.getRank() + recMessage.getContentLength());
//                receiver.changeState(ReceiverState.ESTABLISHED);
//
//                socket.send(new DatagramPacket(new byte[0], 0, request.getAddress(), request.getPort()));
//                break;
//            } catch (IOException e) {
//                e.printStackTrace();
//                logger.error("遇到异常，程序意外终止");
//                System.exit(-1);
//            }
//        }
//
//        logger.info("三次握手成功！连接建立完成！");
//    }

    public static void main(String[] args) {
        if (args.length != 8) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

//        Receiver receiver = new Receiver(args[0], args[1], Integer.parseInt(args[2]),
//                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
//                Double.parseDouble(args[6]), Integer.parseInt(args[7]));
//
//        establishConnection(receiver);

        Thread receiver = new Receiver(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]));
        receiver.start();


        //TODO 对非建立连接的消息进行过滤

    }

    //TODO: sn 将获取到的二进制字节流转化为文档

    /**
     * 将字节数写入文件输出流fileOutputStream中
     * @param data
     * @return
     */
    public void writeIntoFile(byte[] data) {
        try {
            fileOutputStream.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 在数据传送阶段，当Receiver收到来自Sender的data packet后，构造要发送回Sender的ACK Message
     */
    private void setACKMessage() {
        toSendMessage = new Message();
        toSendMessage.setAcknolegment(toSendAcknolegment);
//        toSendMessage.setSequence(toSendSequence);
    }

    private void setSYNACKMessage() {
        toSendMessage = new Message();
        toSendMessage.setSYN(true);
        toSendMessage.setACK(true);
        toSendMessage.setAcknolegment(toSendAcknolegment);
        toSendMessage.setSequence(toSendSequence);
    }

    @Override
    public void run() {
        logger.debug("Receiver run()!");

        changeState(ReceiverState.LISTEN);

        // 创建文件，用于存放从Sender接收到的数据
        File file = new File(filePath);
        if (file.exists()) {
            file.delete();
        }

        try {
            fileOutputStream = new FileOutputStream(file, true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        try {
            datagramSocket = new DatagramSocket(port);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        byte[] buffer = new byte[1024];
        inDatagramPacket = new DatagramPacket(buffer, buffer.length);

        // Receiver不停地接收Sender发送的数据
        while (true) {
            try {
                datagramSocket.receive(inDatagramPacket);
            } catch (IOException e) {
                e.printStackTrace();
            }

            des_address = inDatagramPacket.getSocketAddress();
            receiveBuffer = inDatagramPacket.getData();
            logger.debug("receiveBuffer: {}",receiveBuffer);
            receivedMessage = Message.deMessage(receiveBuffer);

            logger.info("Receiver: receiverState--{}",receiverState);

            // 收到SYN
            if (receivedMessage.isSYN() && receiverState == ReceiverState.LISTEN) {
                logger.debug("Receiver: receive SYN.");
                changeState(ReceiverState.SYN_RECEIVED);
                // TODO: 2019-06-03 发送SYN ACK
                toSendAcknolegment = receivedMessage.getSequence()+1;
                setSYNACKMessage();
                toSendPacket = toSendMessage.enMessage();

                // 发送SYN ACK packet
                try {
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, des_address);
                    datagramSocket.send(outDatagramPacket);
                    logger.debug("Receiver: send SYN ACK.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                lastSendSequence = toSendSequence;
                lastSendAcknolegment = toSendAcknolegment;

            } else if (receivedMessage.isACK() && receiverState == ReceiverState.SYN_RECEIVED && receivedMessage.getSequence() == lastSendAcknolegment && receivedMessage.getAcknolegment() == lastSendSequence + 1) {  // 收到ACK
                logger.debug("Receiver: receive ACK.");
                changeState(ReceiverState.ESTABLISHED);
                // TODO: 2019-06-03 好像除了改变状态并不需要做什么
            } else if (receivedMessage.isFIN()) {  // 如果是连接终止请求
                try {
                    // TODO: 2019-06-03 终止连接
                    logger.debug("Receiver: receive FIN.");
                    changeState(ReceiverState.CLOSED);
                    toSendPacket = receivedMessage.enMessage();
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, des_address);
                    datagramSocket.send(outDatagramPacket);  // 将FIN packet发送回Sender
                    logger.debug("Receiver: send FIN back.");
                    datagramSocket.close();
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (receiverState == ReceiverState.ESTABLISHED) {  // 如果收到data packet
                logger.debug("Receiver: receive data packet.");
                window.put(receivedMessage.getSequence(), receivedMessage.getContent());

                // 将要发送给Sender的acknolegment，暂时先定为已经写入文件的字节数
                toSendAcknolegment = byteHasWrite;

                // 采用累积确认，这里来计算正确的将要发送给Sender的acknoledgment
                while (window.containsKey(toSendAcknolegment)) {
                    // 如果在接收窗口中，没有Receiver期望接收到的sequence，就不必更改toSendAcknolegment
                    // 如果在接收窗口中，已经有了Receiver期望接收到的sequence，就更改toSendAcknolegment，增加该包中数据的长度到toSendAcknolegment中
                    toSendAcknolegment += window.get(toSendAcknolegment).length;
                }

                setACKMessage();
                toSendPacket = toSendMessage.enMessage();
                // 发送ACK packet
                try {
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, des_address);
                    datagramSocket.send(outDatagramPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // 将按顺序到达的data写入文件
                while (window.containsKey(byteHasWrite)) {
                    int length = window.get(byteHasWrite).length;
                    writeIntoFile(window.get(byteHasWrite));
                    window.remove(byteHasWrite);
                    byteHasWrite += length;
                }
            }
            toSendSequence++;
        }
    }
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