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

import static java.lang.Math.max;


/**
 * @author DW
 * @date 2019/5/21
 */
@Data
public class Sender {
	private static final Logger logger = LoggerFactory.getLogger(Sender.class);

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
//	private int receivedAcknolegment;
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

	private DatagramSocket datagramSocket;
	private DatagramPacket inDatagramPacket;
	private DatagramPacket outDatagramPacket;

	private static int seqNum = 100000;  // 请求连接报文中的seq值

	private boolean isNeedRetransmit;  // 是否需要快速重传
	private int retransmitSequence;  // 需要快速重传的数据的第一个字节号


	public static void main(@NotNull String[] args) {
		if (args.length != 11) {
			System.out.println("参数数量不足，请重新启动程序");
			return;
		}

		Sender sender = new Sender(args[0], args[1], Integer.parseInt(args[2]),
				Double.parseDouble(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
				Double.parseDouble(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]),
				Integer.parseInt(args[9]), Integer.parseInt(args[10]));
		// sender.setRandomDelay(new Random((long) sender.seedDelay));
		// sender.setRandomDrop(new Random((long) sender.seedDrop));
//		sender.window = new ArrayList<>(sender.mws);
//		ThreadFactory f = new ThreadFactoryBuilder().setNameFormat("发送数据包-%d").build();
//		sender.toSend = new ThreadPoolExecutor(sender.mws, sender.mws + 2, sender.getInitalTimeout(), TimeUnit.MILLISECONDS,
//				new LinkedBlockingQueue<>(sender.mws), f);
//		establish(sender);
	}

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

		try {
			datagramSocket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		}
		Thread accept = new Accept();
		accept.start();
		Thread connect = new Connect();
		connect.start();
	}

//	/**
//	 * 发送消息
//	 *
//	 * @param message 准备发送的消息
//	 * @return 收到的确认消息
//	 * TODO: yh 这个方法有问题，可以抛弃重写
//	 */
//	private Message sendMessage(@NotNull Message message) {
//		try (DatagramSocket socket = new DatagramSocket(0)) {
//			socket.setSoTimeout(initalTimeout);
//
//			message.setSrcPort(socket.getPort());
//			byte[] messByte = message.enMessage();
//			DatagramPacket request = new DatagramPacket(messByte, messByte.length, InetAddress.getByName(disIP), this.disPort);
//			socket.send(request);
//
//			DatagramPacket response = new DatagramPacket(new byte[mss + Message.HEAD_LENGTH], mss + Message.HEAD_LENGTH);
//			socket.receive(response);
//
//			return Message.deMessage(response.getData());
//		} catch (IOException e) {
//			e.printStackTrace();
//			logger.error("遇到异常，程序意外终止");
//			System.exit(-1);
//		}
//		return null;
//	}

	private void sendMessage() {
		// 如果getDrop()函数返回true，就丢包
		if (!getDrop()) {
			// 获取要发送的packet
			toSendPacket = toSendMessage.enMessage();
			try {
				outDatagramPacket = new DatagramPacket(toSendPacket, toSendPacket.length,
						new InetSocketAddress(disIP, disPort));
				datagramSocket.send(outDatagramPacket);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

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
	 * TODO: hl 获取预构造好的将发送的信息
	 */
	private Message getMessage() {
		Message msg = new Message();
		msg.setDisPort(this.disPort);
		msg.setACK(true);
		msg.setTime(Calendar.getInstance().getTimeInMillis());
		return msg;
	}

	/**
	 * 建立连接
	 * TODO: yh 第一次握手和接收报文都没有问题，但第二次发送报文和之后还没有设计完成
	 * TODO yh 羽涵辛苦了，可能需要设计一下收发包的策略
	 */
//    private static void establish(Sender sender) {
//        try (DatagramSocket socket = new DatagramSocket(0)) {
//            socket.setSoTimeout(sender.initalTimeout);
//
//            STATE1:
//            while (true) {
//                //握手1
//                //准备报文
//                Message m = sender.getMessage();
//                m.setACK(false);
//                m.setSYN(true);
//                m.setRank(sender.getSequence());
//                m.setMss(m.getMss());
//                m.setContent(new byte[0]);
//                m.setSrcPort(socket.getPort());
//                byte[] messByte = m.enMessage();
//                if (sender.getDrop()) {
//                    try {
//                        Thread.sleep(sender.maxDelay);
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                    Sender.logger.info("该数据包丢失，重新发送...");
//                    continue;
//                }
//                //发送报文
//                long temp = Calendar.getInstance().getTimeInMillis();
//                try {
//                    Thread.sleep(sender.getDelay());
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//                Message replyMess;
//                DatagramPacket response;
//                DatagramPacket request = new DatagramPacket(messByte, messByte.length, InetAddress.getByName(sender.disIP), sender.disPort);
//                socket.send(request);
//
//                //接受报文
//                while (true) {
//                    if (Calendar.getInstance().getTimeInMillis() - temp >= sender.maxDelay) {
//                        Sender.logger.info("该数据包超时，重新发送...");
//                        continue STATE1;
//                    }
//
//                    response = new DatagramPacket(new byte[sender.mss + Message.HEAD_LENGTH], sender.mss + Message.HEAD_LENGTH);
//                    socket.receive(response);
//                    replyMess = Message.deMessage(response.getData());
//
//                    //验证报文
//                    if (!replyMess.isSYN() | !replyMess.isACK() | replyMess.getSeq() != sender.getSequence() + 1) {
//                        logger.error("上述报文非同步报文");
//                        continue;
//                    }
//                    break;
//                }
//
//                //更新状态
//                sender.changeState(SenderState.SYN_SENT);
//                sender.setSequence(sender.getSequence() + 1);
//                sender.setRcvdSequence(replyMess.getRank() + 1);
//
//                //握手3
//                //准备报文
//                m = sender.getMessage();
//                m.setRank(sender.getSequence());
//                m.setContent(new byte[0]);
//                m.setSeq(sender.getRcvdSequence());
//
//                //发送报文
//                sender.sendMessage(m);
//
//                //更新状态
//                sender.changeState(SenderState.ESTABLISHED);
//
//                break;
//            }
//            logger.info("三次握手成功！连接建立完成！");
//
//        } catch (IOException e) {
//            e.printStackTrace();
//            logger.error("遇到异常，程序意外终止");
//            System.exit(-1);
//        }
//    }

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
		boolean b = false;
//		boolean b = (pDrop > 0) && randomDrop.nextDouble() < pDrop;
		logger.debug("本次丢包情况:{}", b ? "丢包" : "不丢包");
		return b;
	}


//    private void addSendThread(Sender sender, DatagramSocket socket, byte[] messByte) {
//        int times = 0;
//        while (times < 4) {
//            //丢失直接等待
//            if (sender.getDrop()) {
//                try {
//                    Thread.sleep(sender.maxDelay);
//                    times++;
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//                Sender.logger.info("该数据包丢失，重新发送...");
//            }
//            //延迟
//            try {
//                Thread.sleep(sender.getDelay());
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//            sender.window.add(new SendThread(sender, socket, messByte));
//
//        }
//        Sender.logger.error("网络环境太差，请重新设置相关参数");
//        System.exit(-1);
//    }

//    private void getResponseThread() throws IOException {
//
//    }

	/**
	 * 创建发送线程
	 *
	 */
//    static class SendThread extends Thread {
//        private Sender sender;
//        private DatagramSocket socket;
//        private byte[] messByte;
//        private byte[] replyByte;
//
//        SendThread(Sender sender, DatagramSocket socket, byte[] messByte) {
//            this.sender = sender;
//            this.socket = socket;
//            this.messByte = messByte;
//            this.replyByte = new byte[sender.mss + Message.HEAD_LENGTH];
//        }
//
//        @Override
//        public synchronized void run() {
//            super.run();
//            long temp = Calendar.getInstance().getTimeInMillis();
//            long delayTime = sender.getDelay();
//            try {
//                Thread.sleep(sender.getDelay());
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//
//            try {
//                DatagramPacket request = new DatagramPacket(messByte, messByte.length, InetAddress.getByName(sender.disIP), sender.disPort);
//                socket.send(request);
//                sender.getResponseThread();
//                if (delayTime < sender.maxDelay) {
//                    wait(sender.maxDelay - delayTime);
//                }
//            } catch (IOException | InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
//    }

	//TODO: sn 读取文件并转化为二进制
	//TODO: dyh 发送消息策略


	/**
	 * 用于建立连接。只要连接还没有建立，就一直尝试建立连接。
	 */
	class Connect extends Thread {
		@Override
		public void run() {
			logger.debug("Connect run()!");

			// 只要连接还没有建立，就一直尝试建立连接
			while (senderState != SenderState.ESTABLISHED) {
				logger.info("Sender: senderState--{}", senderState);
				// 发送"连接请求报文"，即SYN packet
				setSYNMessage(seqNum++);
				sendMessage();
				logger.debug("Sender: send SYN.");
				if (senderState != SenderState.SYN_SENT) {
					changeState(SenderState.SYN_SENT);
				}

				// 在发送完一个SYN packet后，该线程等待2m，然后检查是否收到SYN ACK packet
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				// 如果没有收到Receiver的回复，就继续发送"连接请求报文"
				if (receivedMessage == null) {
					continue;
				}
				// 如果没有收到SYNACK，就继续发送"连接请求报文"
				if (!receivedMessage.isSYNACK()) {
					continue;
				}
				// 如果收到了SYN ACK，就发送ACK
				logger.debug("Sender: receive SYN ACK.");
				toSendSequence = receivedMessage.getAcknolegment();
				toSendAcknolegment = receivedMessage.getSequence() + 1;
				setACKMessage();
				sendMessage();
				changeState(SenderState.ESTABLISHED);
				logger.debug("Sender: send ACK.");
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			// 连接已经建立，可以传输文件了
			Thread transfer = new Transfer();
			transfer.start();
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
		}
	}

	/**
	 * 用于接收
	 */
	class Accept extends Thread {
		@Override
		public void run() {
			logger.debug("Accept run()!");

			int duplicateACK = 1;  // 记录重复收到的ACK数量

			while (true) {
				// 接收Receiver发送的数据
				receiveMessage();

				logger.info("Sender: senderState--{}", senderState);

				// 如果Sender接收到的packet是SYN ACK packet，那么就改变Sender状态为SYN_ACK
				if (receivedMessage.isSYNACK() && senderState == SenderState.SYN_SENT && receivedMessage.getAcknolegment() == seqNum + 1) {
					logger.debug("Sender: receive SYN ACK.");
					changeState(SenderState.ESTABLISHED);
				} else if (receivedMessage.isACK() && senderState == SenderState.ESTABLISHED) {
					// 如果Sender接收到的packet是ACK packet
					// 如果收到的来自Receiver的acknolegment比Sender所记录的已经确认收到的ACK字节号大，说明Sender发送的数据都已经收到了
					logger.debug("Sender: receive ACK. byteHasAcked:{}, receivedMessage.getAcknolegment():{}",
							byteHasAcked,receivedMessage.getAcknolegment());
					if (byteHasAcked < receivedMessage.getAcknolegment()) {
						byteHasAcked = receivedMessage.getAcknolegment();  // 更新已经确认的ACK号
						logger.debug("update byteHasAcked:{}",byteHasAcked);
						// 移动滑动窗口的左侧
						left = receivedMessage.getAcknolegment();
						duplicateACK = 1;
						// 移动滑动窗口的右侧
						if (fileLength >= left + mws) {
							right = left + mws;
						} else {
							right = left + fileLength - left;
						}
					}
				} else if (receivedMessage.getAcknolegment() == byteHasAcked) {  // 收到重复的ACK Num确认号
					duplicateACK++;
					logger.debug("Sender: receive duplicate ACK{}.", duplicateACK);
					if (duplicateACK >= 3 && receivedMessage.getAcknolegment() < fileLength) {
						// 快速重传：
						/*
						 * 快速重传机制：基于接收端的反馈信息（ACK）来引发重传,而非重传计时器的超时。不以时间驱动，而以数据驱动重传。也就是说，如果，包没有连续到达，就ack
						 * 最后那个可能被丢了的包，如果发送方连续收到3次相同的ack，就重传。Fast Retransmit的好处是不用等timeout了再重传。
						 */
						// TODO: 2019-06-03
						// 重传packet
						isNeedRetransmit = true;
						retransmitSequence = receivedMessage.getAcknolegment();
					}
				} else if (receivedMessage.isFIN()) {
					logger.debug("Sender: receive FIN.");
					changeState(SenderState.CLOSED);
					datagramSocket.close();
					try {
						bufferedInputStream.close();
					} catch (IOException e) {
						e.printStackTrace();
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
		private int lastSendSequence;
		private byte[] toSendData;

		Transfer() {
			try {
				FileInputStream fileInputStream = new FileInputStream(filePath);
				fileLength = fileInputStream.available();
				bufferedInputStream = new BufferedInputStream(fileInputStream);
				logger.info("Sender: read file content. 文件长度：{} Byte", fileLength);
			} catch (IOException e) {
				e.printStackTrace();
			}
			// 初始化滑动窗口的左右两边
			left = 0;
			right = left + mws > fileLength ? fileLength : left + mws;
			logger.debug("初始化滑动窗口，left：{}，right：{}，mws：{}", left, right, mws);
		}

		private void setDataMessage() {
			toSendMessage = new Message();
			toSendMessage.setACK(false);
			toSendMessage.setSYN(false);
			toSendMessage.setFIN(false);
			toSendMessage.setRST(false);
			toSendMessage.setSequence(toSendSequence);
			toSendMessage.setContent(toSendData);
			byte[] toSendCRC = CRC16.generateCRC(toSendData);
			toSendMessage.setCrc16(toSendCRC);
			toSendMessage.setTime((new Date()).getTime());
		}

		private void sendMessageBySequence(int sequence) {
			try {
				logger.debug("sendMessageBySequence: sequence-{}", sequence);
				int dataLength = sequence + mss <= right ? mss : right - sequence;
				toSendData = new byte[dataLength];
				bufferedInputStream.read(toSendData, 0, dataLength);
				logger.debug("toSendData:{}", toSendData);
				toSendSequence = sequence;
				setDataMessage();
				// 发送data packet
				sendMessage();
				hasSentButNotAcked.put(toSendSequence, toSendData);
				byteHasSent += dataLength;
				logger.debug("已经发送的比特数量：{}", byteHasSent);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		private void retransmit(int sequence) {
			toSendSequence = sequence;
			toSendData = hasSentButNotAcked.get(sequence);
			setDataMessage();
			sendMessage();
			logger.debug("已经重新发送sequence：{}",sequence);
		}

		private void setFINMessage() {
			toSendMessage = new Message();
			toSendMessage.setFIN(true);
		}

		@Override
		public void run() {
			logger.debug("Transfer run()!");

			while (senderState == SenderState.ESTABLISHED) {
				// 将byteHasSent-right段的数据全部发送出去
				while (byteHasAcked <= fileLength) {
					while (byteHasSent < right) {
						sendMessageBySequence(byteHasSent);
						logger.debug("已经发送的字节数量：{}, 窗口：{}--{}", byteHasSent, left, right);
					}

					// 根据指定的序列号重传数据
					if (isNeedRetransmit) {
						logger.info("快速重传：字节序号{}", retransmitSequence);
						retransmit(retransmitSequence);
					}

					// 如果文件发送完了，就发送FIN packet
					if (byteHasAcked == fileLength) {
						setFINMessage();
						sendMessage();
					}
				}
			}
		}
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