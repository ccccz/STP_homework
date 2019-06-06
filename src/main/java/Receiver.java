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
import java.util.Date;
import java.util.HashMap;

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

/**
 * @author DW
 * @date 2019/5/22
 */
@Data
public class Receiver {
    private static final Logger logger = LoggerFactory.getLogger(Sender.class);
    private final int port;
    private final double pDrop;
    private final int seedDrop;
    private final int maxDelay;
    private final double pDelay;
    private final int seedDelay;
    @NonNull
    private String filePath;
    @NonNull
    private String ip;
    private ReceiverState receiverState = ReceiverState.CLOSED;
    private int mss = 0;

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
    private int byteHasWrite = 0;
    /**
     * 将要发送给Sender的sequence
     */
    private int toSendSequence = 0;
    /**
     * 将要发送给Sender的acknolegment
     */
    private int toSendAcknowlegment = 0;
    private int lastSendSequence = 0;
    private int lastSendAcknowlegment = 0;

    private SocketAddress des_address;

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

        Receiver receiver = new Receiver(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]));
        receiver.connect();
    }

    /**
     * 改变接收方状态
     */
    private void changeState(ReceiverState s) {
        this.receiverState = s;
        logger.info("服务端状态改变为{}", s);
    }

    /**
     * 将字节数写入文件输出流fileOutputStream中
     *
     * @param data
     * @return
     */
    public void writeIntoFile(byte[] data) {
        try {
            fileOutputStream.write(data);
            logger.debug("write into file:{}", data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 在数据传送阶段，当Receiver收到来自Sender的data packet后，构造要发送回Sender的ACK Message
     */
    private void setACKMessage() {
        toSendMessage = new Message();
        toSendMessage.setACK(true);
        toSendMessage.setAcknolegment(toSendAcknowlegment);
        toSendMessage.setSequence(toSendSequence);
        toSendMessage.setContent(new byte[]{});
        toSendMessage.setTime((new Date()).getTime());
    }

    private void setSYNACKMessage() {
        toSendMessage = new Message();
        toSendMessage.setSYN(true);
        toSendMessage.setACK(true);
        toSendMessage.setAcknolegment(toSendAcknowlegment);
        toSendMessage.setSequence(toSendSequence);
        toSendMessage.setContent(new byte[]{});
        toSendMessage.setTime((new Date()).getTime());
    }

    public void receiveMessage() {
        try {
            datagramSocket.receive(inDatagramPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }

        des_address = inDatagramPacket.getSocketAddress();
        receiveBuffer = inDatagramPacket.getData();
        receivedMessage = Message.deMessage(receiveBuffer);

//        logger.info("Receiver: receiverState--{}",receiverState);
    }

    /**
     * 建立连接阶段
     */
    public void connect() {
        logger.debug("Receiver run()!");

        changeState(ReceiverState.LISTEN);

        // 一些初始化操作
        // 创建文件，用于存放从Sender接收到的数据
        File file = new File(filePath);
        logger.debug("create file to save data:{}", file.getPath());
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
//        while (true) {
//            try {
//                datagramSocket.receive(inDatagramPacket);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//
//            des_address = inDatagramPacket.getSocketAddress();
//            receiveBuffer = inDatagramPacket.getData();
////            logger.debug("receiveBuffer: {}",receiveBuffer);
//            receivedMessage = Message.deMessage(receiveBuffer);
//
//            logger.info("Receiver: receiverState--{}",receiverState);
//
//            // 收到SYN
//            if (receivedMessage.isSYN() && receiverState == ReceiverState.LISTEN) {
//                logger.debug("Receiver: receive SYN.");
//                changeState(ReceiverState.SYN_RECEIVED);
//                toSendAcknowlegment = receivedMessage.getSequence()+1;
//                setSYNACKMessage();
//                toSendPacket = toSendMessage.enMessage();
//
//                // 发送SYN ACK packet
//                try {
//                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, des_address);
//                    datagramSocket.send(outDatagramPacket);
//                    logger.debug("Receiver: send SYN ACK.");
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//                lastSendSequence = toSendSequence;
//                lastSendAcknowlegment = toSendAcknowlegment;
//
//            } else if (receivedMessage.isACK() && receiverState == ReceiverState.SYN_RECEIVED && receivedMessage.getSequence() == lastSendAcknowlegment && receivedMessage.getAcknolegment() == lastSendSequence + 1) {  // 收到ACK
//                logger.debug("Receiver: receive ACK.");
//                changeState(ReceiverState.ESTABLISHED);
//            }
//        }

        // Recevier不停地检查是否收到Sender发送的SYN
        while (true) {
            // Receiver不停地接收来自Sender发送的“连接阶段”packet
            receiveMessage();

            // 如果收到SYN
            if (receivedMessage.isSYN() && receiverState == ReceiverState.LISTEN) {
                logger.debug("Receiver: receive SYN.");
                changeState(ReceiverState.SYN_RECEIVED);
                toSendAcknowlegment = receivedMessage.getSequence() + 1;
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
                lastSendAcknowlegment = toSendAcknowlegment;

                // 跳出循环
                break;
            }
        }

        // Recevier不停地检查是否收到Sender发送的ACK
        while (true) {
            receiveMessage();

            // 如果收到ACK
            if (receivedMessage.isACK() && receiverState == ReceiverState.SYN_RECEIVED && receivedMessage.getSequence() == lastSendAcknowlegment && receivedMessage.getAcknolegment() == lastSendSequence + 1) {  // 收到ACK
                logger.debug("Receiver: receive ACK.");
                changeState(ReceiverState.ESTABLISHED);
                break;
            }
        }

        // 开始接收data packet
        receive();
    }

    public void receive() {
//        logger.error("receive()");
        while (true) {
            // 接收来自Sender发送的数据
            receiveMessage();
//            logger.error("receive message");


            // 根据Sender发送的data packet来做出相应的操作
            if (receivedMessage.isFIN()) {  // 如果是连接终止请求
                try {
                    logger.debug("Receiver: receive FIN.");
                    changeState(ReceiverState.CLOSED);
                    toSendPacket = receivedMessage.enMessage();
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, des_address);
                    datagramSocket.send(outDatagramPacket);  // 将FIN packet发送回Sender
                    logger.debug("Receiver: send FIN back.");
                    datagramSocket.close();
                    fileOutputStream.close();
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (receiverState == ReceiverState.ESTABLISHED) {  // 如果收到data packet
//                logger.debug("Receiver: receive data packet.");
                window.put(receivedMessage.getSequence(), receivedMessage.getContent());

                // 将要发送给Sender的acknolegment，暂时先定为已经写入文件的字节数
                toSendAcknowlegment = byteHasWrite;

                // 采用累积确认，这里来计算正确的将要发送给Sender的acknoledgment
                while (window.containsKey(toSendAcknowlegment)) {
                    // 如果在接收窗口中，没有Receiver期望接收到的sequence，就不必更改toSendAcknolegment
                    // 如果在接收窗口中，已经有了Receiver期望接收到的sequence，就更改toSendAcknolegment，增加该包中数据的长度到toSendAcknolegment中
                    toSendAcknowlegment += window.get(toSendAcknowlegment).length;
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
                logger.error("send ACK:{}", toSendAcknowlegment);

                // 将按顺序到达的data写入文件
                logger.error("window:{},byteHasWrite:{}", window, byteHasWrite);
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