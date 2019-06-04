import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * @author DW
 * @date 2019/5/22
 */

@Data
public class Message {
    private static final Logger logger = LoggerFactory.getLogger(Sender.class);

    public static final int HEAD_LENGTH = 27;
    private static final int CLEAN = 0xff;
    /**
     * 源端口号
     */
    private int srcPort;
    /**
     * 目地端口号
     */
    private int disPort;
    /**
     * 本包的序号
     */
    private int sequence;
    /**
     * 数据包的确认号，是下一个发送的值
     */
    private int acknolegment;
    /**
     * ACK flag
     */
    private boolean ACK;
    /**
     * 复位
     */
    private boolean RST;
    /**
     * SYN flag
     */
    private boolean SYN;
    /**
     * FIN flag
     */
    private boolean FIN;
    private short window;
    /**
     * 数据最大长度字节: 1500 - 20(IP) - 8 (UDP) = 1472  short:32768‬
     */
    private short mss;
    private long time;
    /**
     * 本包装载的数据长度
     */
    private short contentLength;
    /**
     * 本包装载的数据
     */
    private byte[] content;

    /**
     * 将Message对象实例转换为byte[]数组
     * @return
     */
    byte[] enMessage() {
        logger.debug("准备报文长度：{},内容长度:{},本包序号:{},本包确认号:{}", this.contentLength + Message.HEAD_LENGTH, this.contentLength, this.sequence, this.acknolegment);

        byte[] head = new byte[HEAD_LENGTH];
        Arrays.fill(head, (byte) 0);

        head[0] = (byte) ((srcPort >>> 8) & CLEAN);
        head[1] = (byte) (srcPort & CLEAN);

        head[2] = (byte) ((disPort >>> 8) & CLEAN);
        head[3] = (byte) (disPort & CLEAN);

        head[4] = (byte) ((sequence >>> 24) & CLEAN);
        head[5] = (byte) ((sequence >>> 16) & CLEAN);
        head[6] = (byte) ((sequence >>> 8) & CLEAN);
        head[7] = (byte) ((sequence & CLEAN));

        head[8] = (byte) ((acknolegment >>> 24) & CLEAN);
        head[9] = (byte) ((acknolegment >>> 16) & CLEAN);
        head[10] = (byte) ((acknolegment >>> 8) & CLEAN);
        head[11] = (byte) ((acknolegment & CLEAN));

        if (ACK) {
            head[12] |= 0b10000000;
        }
        if (RST) {
            head[12] |= 0b01000000;
        }
        if (SYN) {
            head[12] |= 0b00100000;
        }
        if (FIN) {
            head[12] |= 0b00010000;
        }
        //此处有4位空闲

        head[13] = (byte) ((window >>> 8) & CLEAN);
        head[14] = (byte) (window & CLEAN);

        head[15] = (byte) ((mss >>> 8) & CLEAN);
        head[16] = (byte) (mss & CLEAN);

        head[17] = (byte) ((time >>> 56) & CLEAN);
        head[18] = (byte) ((time >>> 48) & CLEAN);
        head[19] = (byte) ((time >>> 40) & CLEAN);
        head[20] = (byte) ((time >>> 32) & CLEAN);
        head[21] = (byte) ((time >>> 24) & CLEAN);
        head[22] = (byte) ((time >>> 16) & CLEAN);
        head[23] = (byte) ((time >>> 8) & CLEAN);
        head[24] = (byte) (time & CLEAN);

        head[25] = (byte) ((contentLength & CLEAN) >>> 8);
        head[26] = (byte) (contentLength & 0x00ff);

        // 如果该Message中的content字段为空
        if (this.content==null||this.content.length==0||this.contentLength==0) {
            return head;
        }
        head = Arrays.copyOf(head, head.length + this.content.length);
//        logger.debug("装载的内容长度：{}",this.content.length);
        System.arraycopy(this.content, 0, head, HEAD_LENGTH, this.content.length);
        return head;
    }

    /**
     * 将byte[]数组转换为Message对象实例
     * @param message
     * @return
     */
    static Message deMessage(byte[] message) {
        Message m = new Message();
        m.setSrcPort(((message[0] & CLEAN) << 8) | message[1] & CLEAN);
        m.setDisPort(((message[2] & CLEAN) << 8) | message[3] & CLEAN);
        m.setSequence(((message[4] & CLEAN) << 24) | ((message[5] & CLEAN) << 16) | ((message[6] & CLEAN) << 8) | (message[7] & CLEAN));
        m.setAcknolegment(((message[8] & CLEAN) << 24) | ((message[9] & CLEAN) << 16) | ((message[10] & CLEAN) << 8) | (message[11] & CLEAN));

        m.setACK((message[12] & 128) == 128);
        m.setRST((message[12] & 64) == 64);
        m.setSYN((message[12] & 32) == 32);
        m.setFIN((message[12] & 16) == 16);

        m.setWindow((short) (((message[13] & CLEAN) << 8) | message[14] & CLEAN));
        m.setMss((short) (((message[15] & CLEAN) << 8) | message[16] & CLEAN));
        m.setTime(((long) (message[17] & CLEAN) << 56) | ((long) (message[18] & CLEAN) << 48) | ((long) (message[19] & CLEAN) << 40)
                | ((long) (message[20] & CLEAN) << 32) | ((long) (message[21] & CLEAN) << 24) | ((long) (message[22] & CLEAN) << 16) | ((long) (message[23] & CLEAN) << 8) | ((long) message[24] & CLEAN));

        // TODO: 2019-06-03 如果收到的packet中data字段为空呢？
        byte[] c = new byte[m.getContentLength()];
        System.arraycopy(message, HEAD_LENGTH, c, 0, m.getContentLength());
        m.setContent(c);

        // TODO: 2019-06-04 这里的方法有问题：获取dataContent好像不太对
        logger.debug("接收到报文，数据内容长度:{},报文序号{},报文确认号{}", m.contentLength, m.getSequence(),m.getAcknolegment());
        return m;
    }

    void setContent(byte[] content) {
        this.contentLength = (short) content.length;
        this.content = content;
    }

    //TODO yh 检查过时消息
    //TODO hl 可能需要新增一个参数确定是哪个分块


    public void setSrcPort(int srcPort) {
        this.srcPort = srcPort;
    }

    public void setDisPort(int disPort) {
        this.disPort = disPort;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public void setAcknolegment(int acknolegment) {
        this.acknolegment = acknolegment;
    }

    public void setACK(boolean ACK) {
        this.ACK = ACK;
    }

    public void setRST(boolean RST) {
        this.RST = RST;
    }

    public void setSYN(boolean SYN) {
        this.SYN = SYN;
    }

    public void setFIN(boolean FIN) {
        this.FIN = FIN;
    }

    public void setWindow(short window) {
        this.window = window;
    }

    public void setMss(short mss) {
        this.mss = mss;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public void setContentLength(short contentLength) {
        this.contentLength = contentLength;
    }

    public static Logger getLogger() {
        return logger;
    }

    public static int getHeadLength() {
        return HEAD_LENGTH;
    }

    public static int getCLEAN() {
        return CLEAN;
    }

    public int getSrcPort() {
        return srcPort;
    }

    public int getDisPort() {
        return disPort;
    }

    public int getSequence() {
        return sequence;
    }

    public int getAcknolegment() {
        return acknolegment;
    }

    public boolean isACK() {
        return ACK;
    }

    public boolean isRST() {
        return RST;
    }

    public boolean isSYN() {
        return SYN;
    }

    public boolean isFIN() {
        return FIN;
    }

    public short getWindow() {
        return window;
    }

    public short getMss() {
        return mss;
    }

    public long getTime() {
        return time;
    }

    public short getContentLength() {
        return contentLength;
    }

    public byte[] getContent() {
        return content;
    }

    public boolean isSYNACK() {
        return SYN && ACK;
    }
}
