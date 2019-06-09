import lombok.Data;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;

import static java.lang.Math.max;

/**
 * @author DW
 * @date 2019/5/22
 */
@Data
public class Receiver{


    private String filePath;

    private String ip;
    private static int port;
    private static double pDrop;
    private static int seedDrop;
    private static int maxDelay;
    private static double pDelay;
    private static int seedDelay;

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
    private int rcvdSequence;

    private FileOutputStream fileOutputStream;
    DatagramSocket datagramSocket;
    private DatagramPacket inDatagramPacket;  // 从Sender接收
    private DatagramPacket outDatagramPacket;  // 向Sender发送
    private HashMap<Integer, byte[]> window = new HashMap<>();
    private Message receivedMessage;
    private Message toSendMessage;
    private byte[] toSendPacket;
    private byte[] receiveBuffer;
    ArrayList<byte[]> filePieces=new ArrayList<>();

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

    /**
     * 延迟随机数发生器
     */
    private Random randomDelay;
    /**
     * 丢包随机数发生器
     */
    private Random randomDrop;

    public static void main(String[] args) {
        if (args.length != 8) {
            System.out.println("参数数量不足，请重新启动程序");
            return;
        }

        System.out.println("handShake No.2");
        Receiver receiver = new Receiver(args[0], args[1], Integer.parseInt(args[2]),
                Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                Double.parseDouble(args[6]), Integer.parseInt(args[7]));

        receiver.setRandomDelay(new Random((long) receiver.seedDelay));
        receiver.setRandomDrop(new Random((long)receiver.seedDrop));
        receiver.establish();
        receiver.receiveAllMessage();
        receiver.writeFile();
        receiver.datagramSocket.close();
//        int i=1;
//        SocketAddress des;
//        while(true){
//            byte[] bytes=new byte[1];
//            DatagramPacket datagramPacket=new DatagramPacket(bytes,1);
//            try {
//                receiver.datagramSocket.receive(datagramPacket);
//                des=datagramPacket.getSocketAddress();
//
//                if(i==3){
//                    DatagramPacket datagramPacket2=new DatagramPacket(bytes,1,des);
//                    receiver.datagramSocket.send(datagramPacket2);
//                    System.out.println("ＯＫＫＫＫ");
//                }
//                i++;
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//            System.out.println(datagramPacket.getLength());
//        }
    }

    public void establish(){
        while (receiverState!=ReceiverState.ESTABLISHED){
            byte[] bytes=new byte[10000];
            DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length);
            try {
//                datagramSocket=new DatagramSocket(port);
                datagramSocket.receive(datagramPacket);
                bytes=datagramPacket.getData();
                Message message=Message.deMessage(bytes);
                des_address=datagramPacket.getSocketAddress();
                rcvdSequence=message.getSequence();
                if (message.isSYN()){
                    receiverState=ReceiverState.ESTABLISHED;
                    System.out.println("receiver ESTABLISHE!");
                    Message ackMessage=new Message();
                    ackMessage.setSYN(true);
                    ackMessage.setACK(true);
                    ackMessage.setContentLength((short) 0);
                    sendMessage(ackMessage);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void receiveAllMessage(){
        while(receiverState==ReceiverState.ESTABLISHED){
            byte[] bytes=new byte[10000];
            DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length);
            try {
                datagramSocket.receive(datagramPacket);
                bytes=datagramPacket.getData();
                Message message=Message.deMessage(bytes);
                des_address=datagramPacket.getSocketAddress();
                System.out.println("receive package"+message.getSequence()+"!");
                if (message.isFIN()){
                    Message ackMessage=new Message();
                    ackMessage.setFIN(true);
                    ackMessage.setACK(true);
                    ackMessage.setSequence(message.getSequence());
                    ackMessage.setContentLength((short) 0);
                    System.out.println("receive final package");
                    receiverState=ReceiverState.CLOSED;
                    sendFinMessage(ackMessage);
                }else if(!message.isSYN()){
                    System.out.println("收到数据");
                    if (message.getSequence()==rcvdSequence+1){
                        filePieces.add(message.getContent());
                        rcvdSequence=message.getSequence();
                    }
                    Message ackMessage=new Message();
                    ackMessage.setACK(true);
                    ackMessage.setFIN(false);
                    ackMessage.setContentLength((short) 0);
                    ackMessage.setSequence(message.getSequence());
                    sendMessage(ackMessage);
                }else{
                    rcvdSequence=message.getSequence();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void writeFile(){
        File file = new File(filePath);
        System.out.println("create file to save data:"+file.getPath());
        if (file.exists()) {
            file.delete();
        }
        try {
            fileOutputStream = new FileOutputStream(file, true);
            for (int i=0;i<filePieces.size();i++){
                fileOutputStream.write(filePieces.get(i));
                System.out.println("write into file:"+i);
            }
            fileOutputStream.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 发送Message
     * @param message
     */
    public void sendMessage(Message message){
        byte[] bytes=message.enMessage();
        if (getDrop()){
            return;
        }

        int delay=getDelay();

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length, des_address);
//                datagramSocket = new DatagramSocket();
            datagramSocket.send(datagramPacket);
//                        datagramSocket.close();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
//        if (delay!=0){
//            TimerTask timerTask=new TimerTask() {
//                @Override
//                public void run() {
//                    try {
//                        DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length, des_address);
////                        datagramSocket = new DatagramSocket();
//                        datagramSocket.send(datagramPacket);
//                    } catch (UnknownHostException e) {
//                        e.printStackTrace();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//            };
//
//            Timer timer=new Timer();
//            timer.schedule(timerTask,delay);
//        }else{
//            try {
//                DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length, des_address);
////                datagramSocket = new DatagramSocket();
//                datagramSocket.send(datagramPacket);
////                        datagramSocket.close();
//            } catch (UnknownHostException e) {
//                e.printStackTrace();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }

    }

    public void sendFinMessage(Message message){
        byte[] bytes=message.enMessage();
        try {
            DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length, des_address);
            datagramSocket = new DatagramSocket();
            datagramSocket.send(datagramPacket);
            datagramSocket.close();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public void setRandomDelay(Random randomDelay) {
        this.randomDelay = randomDelay;
    }

    public void setRandomDrop(Random randomDrop) {
        this.randomDrop = randomDrop;
    }

    /**
     * 初始化
     * @param filePath
     * @param ip
     * @param port
     * @param pDrop
     * @param seedDrop
     * @param maxDelay
     * @param pDelay
     * @param seedDelay
     */
    public Receiver(String filePath,String ip,int port,double pDrop,int seedDrop,int maxDelay,double pDelay,int seedDelay){
        this.filePath=filePath;
        this.ip=ip;
        this.port=port;
        this.pDrop=pDrop;
        this.seedDrop=seedDrop;
        this.maxDelay=maxDelay;
        this.pDelay=pDelay;
        this.seedDelay=seedDelay;
        receiverState=ReceiverState.LISTEN;

        try {
            datagramSocket=new DatagramSocket(port);
        } catch (SocketException e) {
            e.printStackTrace();
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