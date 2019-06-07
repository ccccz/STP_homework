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
    @NonNull
    private String filePath;
    @NonNull
    private String ip;
    private final int port;
    private final int maxDelay;
    private int msw;
    private int mss;

    private int wdSize;
    private ReceiverState receiverState = ReceiverState.CLOSED;
    private static final Logger logger = LoggerFactory.getLogger(Sender.class);

    private FileOutputStream fileOutputStream;
    private DatagramSocket datagramSocket;
    private DatagramPacket inDatagramPacket;
    private DatagramPacket outDatagramPacket;
    private volatile HashMap<Integer, byte[]> window = new HashMap<>();

    private Message toSendMessage;
    private byte[] toSendPacket;
    private byte[] receiveBuffer;

    /**
     * 已经写入文件的字节号
     */
    private volatile int byteHasWrite = 0;
    /**
     * 将要发送给Sender的sequence
     */
    private int toSendSequence = 0;
    /**
     * 将要发送给Sender的acknolegment
     */
    private int toSendAcknowlegment = 0;

    private WriteFile writeFile;
    private SocketAddress desAddress;

    private final String lock = "";

    public static void main(String[] args) {
        if (args.length != 6) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

        Receiver receiver = new Receiver(args[0], args[1], Integer.parseInt(args[2]),
                Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
        receiver.connect();
    }

    private Receiver(@NonNull String filePath, @NonNull String ip, int port, int maxDelay, int mss, int msw) {
        this.filePath = filePath;
        this.ip = ip;
        this.port = port;
        this.maxDelay = maxDelay;
        this.writeFile = new WriteFile();
        this.mss = mss;
        this.msw = msw;
        wdSize = msw / mss;
        this.receiveBuffer = new byte[mss + Message.HEAD_LENGTH];
        writeFile.setPriority(Thread.MAX_PRIORITY);
    }

    /**
     * 改变接收方状态
     */
    private void changeState(ReceiverState s) {
        this.receiverState = s;
        logger.info("服务端状态改变为{}", s);
    }

    class WriteFile extends Thread {
        @Override
        public void run() {
            while (receiverState != ReceiverState.CLOSED) {
                if (window.containsKey(byteHasWrite)) {
                    synchronized (lock) {
                        try {
                            while (window.containsKey(byteHasWrite)) {
                                int length = window.get(byteHasWrite).length;
                                fileOutputStream.write(window.get(byteHasWrite));
                                window.remove(byteHasWrite);
                                byteHasWrite += length;
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
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

    private Message receiveMessage() {
        try {
            inDatagramPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            datagramSocket.receive(inDatagramPacket);
            desAddress = inDatagramPacket.getSocketAddress();
            byte[] receive = inDatagramPacket.getData();
            Message message = Message.deMessage(receive);
            logger.warn("接受：msg:{}", message.getSequence());
            return message;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * 建立连接阶段
     */
    private void connect() {
        logger.debug("Receiver run()!");
        changeState(ReceiverState.LISTEN);
        // 一些初始化操作
        // 创建文件，用于存放从Sender接收到的数据
        File file = new File(filePath);
        logger.debug("Receiver: Create file to save data:{}", file.getPath());
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
        inDatagramPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        while (true) {
            // Receiver不停地接收来自Sender发送的“连接阶段”packet
            Message msg = receiveMessage();

            // 如果收到SYN
            if (msg.isSYN() && receiverState == ReceiverState.LISTEN) {
                logger.debug("Receiver: receive SYN.");
                changeState(ReceiverState.SYN_RECEIVED);
                toSendAcknowlegment = msg.getSequence() + 1;
                setSYNACKMessage();
                toSendPacket = toSendMessage.enMessage();

                // 发送SYN ACK packet
                try {
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, desAddress);
                    datagramSocket.send(outDatagramPacket);
                    logger.debug("Receiver: send SYN ACK.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
//                lastSendSequence = toSendSequence;
//                lastSendAcknowlegment = toSendAcknowlegment;

                // 跳出循环
                break;
            }
        }

        // Recevier不停地检查是否收到Sender发送的ACK
        while (true) {
            Message msg = receiveMessage();

            // 如果收到ACK
            if (msg.isACK() && receiverState == ReceiverState.SYN_RECEIVED) {  // 收到ACK
                logger.debug("Receiver: receive ACK.");
                changeState(ReceiverState.ESTABLISHED);
                toSendAcknowlegment = 0;
                break;
            }
        }

        // 开始接收data packet
        writeFile.start();
        receive();
    }

    public void receive() {
        int maxReceived = 0;

        while (true) {
            // 接收来自Sender发送的数据
            Message msg = receiveMessage();

            if (msg.isFIN()) {
                // 如果是连接终止请求
                try {
                    logger.debug("收到中止包");
                    changeState(ReceiverState.CLOSED);
                    toSendPacket = msg.enMessage();
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, desAddress);
                    // 将FIN packet发送回Sender
                    datagramSocket.send(outDatagramPacket);
                    writeFile.interrupt();
                    datagramSocket.close();
                    fileOutputStream.close();
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            } else if (receiverState == ReceiverState.ESTABLISHED) {
                // 如果收到data packet
                logger.info("收到包:{}", msg.getSequence());

                if (window.size() < wdSize) {
                    if (msg.getSequence() > maxReceived) {
                        maxReceived = msg.getSequence();
                    }
                    synchronized (lock) {
                        if (msg.getSequence() >= byteHasWrite) {
                            window.put(msg.getSequence(), msg.getContent());
                            toSendAcknowlegment = msg.getSequence() + msg.getContentLength();
                        } else {
                            toSendAcknowlegment = byteHasWrite;
                        }
                    }
                } else {
                    if (!window.containsKey(byteHasWrite)) {
                        logger.error("?????");
                        System.exit(-1);
                    }
                }
                setACKMessage();
                toSendPacket = toSendMessage.enMessage();
                // 发送ACK packet
                try {
                    outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length, desAddress);
                    datagramSocket.send(outDatagramPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                logger.info("发送确认号:{}", toSendAcknowlegment);
            }
            toSendSequence++;
        }
    }

}