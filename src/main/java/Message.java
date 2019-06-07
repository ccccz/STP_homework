import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * @author DW
 * @date 2019/5/22
 */

public class Message {
    public static final int CRC_LENGTH = 2;
    public static final int HEAD_LENGTH = 29;
    private static final Logger logger = LoggerFactory.getLogger(Sender.class);
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
     * 数据最大长度字节: 1500 - 20(IP) - 8 (UDP) = 1472  short:32768‬ / 2 = 16384
     */
    private short mss;
    private long time;  // todo:发送时间，发送前设置，isDelay前
    /**
     * 本包装载的数据长度
     */
    private short contentLength;
    /**
     * 本包装载的数据
     */
    private byte[] content;

    /**
     * 本包数据的校验码
     */
    private byte[] crc16;

    /**
     * 将byte[]数组转换为Message对象实例
     *
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
        m.setContentLength((short) (((message[25] & CLEAN) << 8) | message[26] & CLEAN));

        byte[] crc = new byte[CRC_LENGTH];
        System.arraycopy(message, 27, crc, 0, CRC_LENGTH);

        m.setCrc16(crc);

        byte[] con = new byte[m.getContentLength()];
        System.arraycopy(message, HEAD_LENGTH, con, 0, m.getContentLength());
        m.setContent(con);

        return m;
    }

    public static int getCLEAN() {
        return CLEAN;
    }

    /**
     * 将Message对象实例转换为byte[]数组
     *
     * @return
     */
    byte[] enMessage() {
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

        head[25] = (byte) ((contentLength >>> 8) & CLEAN);
        head[26] = (byte) (contentLength & 0x00ff);

        //add CRC to head
//        logger.debug("{},{},{}",crc16.length,HEAD_LENGTH-CRC_LENGTH,CRC_LENGTH);
        if (this.crc16 == null) {
            this.crc16 = new byte[2];
        }
        System.arraycopy(this.crc16, 0, head, HEAD_LENGTH - CRC_LENGTH, CRC_LENGTH);

        // 如果该Message中的content字段为空
        if (this.content == null || this.content.length == 0 || this.contentLength == 0) {
            return head;
        }
        head = Arrays.copyOf(head, head.length + this.content.length);
        System.arraycopy(this.content, 0, head, HEAD_LENGTH, this.content.length);

        return head;
    }

    public byte[] getCrc16() {
        return this.crc16;
    }

    public void setCrc16(byte[] crc16) {
        this.crc16 = crc16;
    }

    public int getSrcPort() {
        return srcPort;
    }

    public void setSrcPort(int srcPort) {
        this.srcPort = srcPort;
    }

    public int getDisPort() {
        return disPort;
    }

    public void setDisPort(int disPort) {
        this.disPort = disPort;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public int getAcknolegment() {
        return acknolegment;
    }

    public void setAcknolegment(int acknolegment) {
        this.acknolegment = acknolegment;
    }

    public boolean isACK() {
        return ACK;
    }

    public void setACK(boolean ACK) {
        this.ACK = ACK;
    }

    public boolean isRST() {
        return RST;
    }

    public void setRST(boolean RST) {
        this.RST = RST;
    }

    public boolean isSYN() {
        return SYN;
    }

    public void setSYN(boolean SYN) {
        this.SYN = SYN;
    }

    public boolean isFIN() {
        return FIN;
    }

    public void setFIN(boolean FIN) {
        this.FIN = FIN;
    }

    public short getWindow() {
        return window;
    }

    public void setWindow(short window) {
        this.window = window;
    }

    public short getMss() {
        return mss;
    }

    public void setMss(short mss) {
        this.mss = mss;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public short getContentLength() {
        return contentLength;
    }

    public void setContentLength(short contentLength) {
        this.contentLength = contentLength;
    }

    public byte[] getContent() {
        return content;
    }

    void setContent(byte[] content) {
        this.contentLength = (short) content.length;
        this.content = content;
        this.crc16 = CRC16.generateCRC(content);
    }

    public boolean isSYNACK() {
        return SYN && ACK;
    }


//    public static byte[] hexStringToByteArray(String s) {
//        int len = s.length();
//        byte[] data = new byte[len / 2];
//        for (int i = 0; i < len; i += 2) {
//            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
//                                 + Character.digit(s.charAt(i+1), 16));
//        }
//        return data;
//    }

}
