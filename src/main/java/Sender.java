import lombok.Data;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;


/**
 * @author DW
 * @date 2019/5/21
 */
@Data
public class Sender {
	private static final Logger logger = LoggerFactory.getLogger(Sender.class);

	private String filePath;  //文件路径

	private String disIP;   //接收方程序的IP地址
	private static int disPort;    //接收方程序的端口
	private static double pDrop;    //丢包概率
	private static int seedDrop;    //丢包随机数种子
	private static int maxDelay;    //最大延迟
	private static double pDelay;    //延迟率
	private static int seedDelay;    //
	private static int mss;     //最大分段
	private static int mws;     //最大窗口大小

	private int srcPort;  //发送方端口
	private int initalTimeout;     //初始的超时
	private static final int TIMEOUT=2000;
	ArrayList<byte[]> filePieces;
	private SocketAddress des_address;


	private int fileLength;  // 文件所含有的字节数
	private SenderState senderState = SenderState.CLOSED;
	/**
	 * 目前已经发送成功的字节的数量，不包含本数据包
	 */
	private int byteHasSent;
	/**
	 * 目前已经收到来自Receiver的ACK的字节的数量
	 */
	private int byteHasAcked;
	/**
	 * Sender从Receiver接收到的确认号
	 */
	private int receivedAcknolegment;

	/**
	 * 序号
	 */
	private int sequence;

	/**
	 * 确认号，实际上不需要
	 */
	private int acknolegment;
	/**
	 * 滑动窗口的左侧
	 */
	private int left;
	/**
	 * 滑动窗口的右侧
	 */
	private int right;
	private BufferedInputStream bufferedInputStream;
	//	private byte[] window;
	private HashMap<Integer, byte[]> hasSentButNotAcked=new HashMap<>();  // 存储已经发送的，但还没有收到确认的数据

//    /**
//     * 窗口
//     */
//    private ArrayList<SendThread> window;
//    /**
//     * 发送池
//     */
//    private ThreadPoolExecutor toSend;
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

	DatagramSocket datagramSocket;
	private DatagramSocket datagramSocket2;
	private DatagramPacket inDatagramPacket;
	private DatagramPacket outDatagramPacket;

	private static int seqNum = 100000;  // 请求连接报文中的seq值

	private boolean isNeedRetransmit;  // 是否需要快速重传
	private int retransmitSequence;  // 需要快速重传的数据的第一个字节号

	private Timer sendTimer;  // 计时器

	public static void main(@NotNull String[] args) {
		if (args.length != 11) {
			System.out.println("参数数量不足，请重新启动程序");
			return;
		}

		Sender sender = new Sender(args[0], args[1], Integer.parseInt(args[2]),
				Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
				Double.parseDouble(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]),
				Integer.parseInt(args[9]), Integer.parseInt(args[10]));


		sender.setRandomDelay(new Random((long) sender.seedDelay));
		sender.setRandomDrop(new Random((long) sender.seedDrop));
		sender.readFile();
		sender.establish();

		sender.sendAllMessage();
		sender.closed();

//		while(true){
//			try {
//				DatagramPacket datagramPacket=new DatagramPacket(new byte[1],1,InetAddress.getByName("localhost"),disPort);
//				sender.datagramSocket.send(datagramPacket);
//				Thread.sleep(10000);
//				System.out.println("fabao!");
//			} catch (UnknownHostException e) {
//				e.printStackTrace();
//			} catch (IOException e) {
//				e.printStackTrace();
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
//
//		}
	}

	/**
	 * 初始化
	 * @param filePath
	 * @param disIP
	 * @param disPort
	 * @param pDrop
	 * @param seedDrop
	 * @param maxDelay
	 * @param pDelay
	 * @param seedDelay
	 * @param mss
	 * @param mws
	 * @param initalTimeout
	 */
	public Sender(@NonNull String filePath, @NonNull String disIP, int disPort, double pDrop, int seedDrop, int maxDelay, double pDelay, int seedDelay, int mss, int mws, @NonNull int initalTimeout) {
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
		this.sequence=0;
		this.acknolegment=23;
		srcPort=60101;
		filePieces=new ArrayList<>();

		try {
			datagramSocket = new DatagramSocket();
//			datagramSocket2=new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 建立连接
	 * @return
	 */
	public void establish(){

		System.out.println("handShake No.1");

		while(senderState != SenderState.ESTABLISHED){
			sequence++;
			Message message=new Message(disPort,sequence,acknolegment,false,false,true,false,(short) mws,(short)mss,new Date().getTime(),(short)0,new byte[0],CRC16.generateCRC(new byte[]{}));
			sendMessage(message);

			byte[] bytes=new byte[10000];
			DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length);
			try {
//				datagramSocket=new DatagramSocket();
				datagramSocket.setSoTimeout(TIMEOUT);
				datagramSocket.receive(datagramPacket);
				des_address=datagramPacket.getSocketAddress();
				bytes=datagramPacket.getData();
				Message ackMessage=Message.deMessage(bytes);
				if (message.isSYN()){
					senderState=SenderState.ESTABLISHED;
					System.out.println("sender ESTABLISHE!");
				}
			} catch (SocketTimeoutException e){
//				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	/**
	 * 读取文件
	 */
	private void readFile(){
		FileInputStream fileInputStream = null;
		try {
			fileInputStream = new FileInputStream(filePath);
			fileLength = fileInputStream.available();
			bufferedInputStream = new BufferedInputStream(fileInputStream);
			System.out.println("Sender: read file content. 文件长度："+fileLength+" Byte" );
			int i=0;
			for (i=0;i+mss<=fileLength;i=i+mss){
				byte[] tempByte=new byte[mss];
				bufferedInputStream.read(tempByte,0,mss);
				filePieces.add(tempByte);
			}
			if (i<fileLength){
				byte[] tempByte=new byte[fileLength-i];
				bufferedInputStream.read(tempByte,0,fileLength-i);
				filePieces.add(tempByte);
			}
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

//		//等待
//		try {
//			Thread.sleep(200);
//		} catch (InterruptedException e) {
//			e.printStackTrace();
//		}

		int delay=getDelay();

		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		try {
			DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length,InetAddress.getByName("localhost"),disPort);
//						Thread.sleep(500);
			datagramSocket.send(datagramPacket);
			System.out.println("send package "+sequence+" !");
//						datagramSocket.close();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
//		if (delay!=0){
//			TimerTask timerTask=new TimerTask() {
//				@Override
//				public void run() {
//					try {
//						DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length,InetAddress.getByName("localhost"),disPort);
////						Thread.sleep(500);
//						datagramSocket.send(datagramPacket);
//						System.out.println("send package "+sequence+" !");
////						datagramSocket.close();
//					} catch (UnknownHostException e) {
//						e.printStackTrace();
//					} catch (IOException e) {
//						e.printStackTrace();
//					}
//				}
//			};
//
//			Timer timer=new Timer();
//			timer.schedule(timerTask,delay);
//		}else{
//			try {
//				DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length,InetAddress.getByName("localhost"),disPort);
////				Thread.sleep(500);
//				datagramSocket.send(datagramPacket);
//				System.out.println("send package "+sequence+" !");
////						datagramSocket.close();
//			} catch (UnknownHostException e) {
//				e.printStackTrace();
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//		}

	}

	/**
	 * 发送所有数据
	 */
	public void sendAllMessage(){
		boolean isOK=false;
		for (int i=0;i<filePieces.size();i++){
			isOK=false;
			sequence++;
			while(!isOK){
//				sequence++;
				Message message=new Message(disPort,sequence,acknolegment,false,false,false,false,(short) mws,(short)mss,new Date().getTime(),(short)0,filePieces.get(i),CRC16.generateCRC(filePieces.get(i)));
				sendMessage(message);

				byte[] bytes=new byte[10000];
				DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length);
				try {
//				datagramSocket=new DatagramSocket();
					datagramSocket.setSoTimeout(TIMEOUT);
					datagramSocket.receive(datagramPacket);
					des_address=datagramPacket.getSocketAddress();
					bytes=datagramPacket.getData();
					Message ackMessage=Message.deMessage(bytes);
					if (ackMessage.getSequence()==sequence){
						System.out.println("package "+sequence+" is ok");
						isOK=true;
					}
				} catch (SocketTimeoutException e){
//				e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * 断开连接
	 */
	public void closed(){
		sequence++;
		while(senderState!=SenderState.CLOSED) {
			Message message=new Message(disPort,sequence,acknolegment,false,false,false,true,(short) mws,(short)mss,new Date().getTime(),(short)0,new byte[0],CRC16.generateCRC(new byte[]{}));
			sendMessage(message);

			byte[] bytes=new byte[10000];
			DatagramPacket datagramPacket=new DatagramPacket(bytes,bytes.length);
			try {
//				datagramSocket=new DatagramSocket();
				datagramSocket.setSoTimeout(TIMEOUT);
				datagramSocket.receive(datagramPacket);
				bytes=datagramPacket.getData();
				Message ackMessage=Message.deMessage(bytes);
				if (ackMessage.isFIN()  && ackMessage.isACK()){
					senderState=SenderState.CLOSED;
					System.out.println("sender CLOSED!");
					datagramSocket.close();
				}
			} catch (SocketTimeoutException e){
//				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	/**
	 * @return 延迟时间
	 */
	private int getDelay() {
		int time = (int) ((pDelay <= 0) ? 0 : max(0, randomDelay.nextDouble() - 1.0 + pDelay) / pDelay * maxDelay);
		System.out.println("本次延迟时间"+ time);
		return time;
	}

	/**
	 * @return true：丢包，false：不丢包
	 */
	private boolean getDrop() {
		boolean b = (pDrop > 0) && randomDrop.nextDouble() < pDrop;
		System.out.println("本次丢包情况"+( b ? "丢包" : "不丢包"));
		return b;
	}

	public void setRandomDelay(Random randomDelay) {
		this.randomDelay = randomDelay;
	}

	public void setRandomDrop(Random randomDrop) {
		this.randomDrop = randomDrop;
	}
}

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