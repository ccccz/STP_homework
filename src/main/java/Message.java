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
    private int srcPort;
    private int disPort;
    /**
     * 本包的序号
     */
    private int rank;
    /**
     * 数据包的确认号，是下一个发送的值
     */
    private int seq;
    private boolean ack;
    /**
     * 复位
     */
    private boolean rst;
    private boolean syn;
    private boolean fin;
    private short window;
    /**
     * 数据最大长度字节: 1500 - 20(IP) - 8 (UDP) = 1472  short:32768‬
     */
    private short mss;
    private long time;
    private short contentLength;
    private byte[] content;

    byte[] enMessage() {
        logger.debug("准备报文长度：{},内容长度:{},本包序号:{},本包确认号:{}", this.contentLength + Message.HEAD_LENGTH, this.contentLength, this.rank, this.seq);

        byte[] head = new byte[HEAD_LENGTH];
        Arrays.fill(head, (byte) 0);

        head[0] = (byte) ((srcPort >>> 8) & CLEAN);
        head[1] = (byte) (srcPort & CLEAN);

        head[2] = (byte) ((disPort >>> 8) & CLEAN);
        head[3] = (byte) (disPort & CLEAN);

        head[4] = (byte) ((rank >>> 24) & CLEAN);
        head[5] = (byte) ((rank >>> 16) & CLEAN);
        head[6] = (byte) ((rank >>> 8) & CLEAN);
        head[7] = (byte) ((rank & CLEAN));

        head[8] = (byte) ((seq >>> 24) & CLEAN);
        head[9] = (byte) ((seq >>> 16) & CLEAN);
        head[10] = (byte) ((seq >>> 8) & CLEAN);
        head[11] = (byte) ((seq & CLEAN));

        if (ack) {
            head[12] |= 0b10000000;
        }
        if (rst) {
            head[12] |= 0b01000000;
        }
        if (syn) {
            head[12] |= 0b00100000;
        }
        if (fin) {
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

        head = Arrays.copyOf(head, head.length + this.content.length);
        System.arraycopy(this.content, 0, head, head.length, this.content.length);
        return head;
    }

    static Message deMessage(byte[] message) {
        Message m = new Message();
        m.setSrcPort(((message[0] & CLEAN) << 8) | message[1] & CLEAN);
        m.setDisPort(((message[2] & CLEAN) << 8) | message[3] & CLEAN);
        m.setRank(((message[4] & CLEAN) << 24) | ((message[5] & CLEAN) << 16) | ((message[6] & CLEAN) << 8) | (message[7] & CLEAN));
        m.setSeq(((message[8] & CLEAN) << 24) | ((message[9] & CLEAN) << 16) | ((message[10] & CLEAN) << 8) | (message[11] & CLEAN));

        m.setAck((message[12] & 128) == 128);
        m.setRst((message[12] & 64) == 64);
        m.setSyn((message[12] & 32) == 32);
        m.setFin((message[12] & 16) == 16);

        m.setWindow((short) (((message[13] & CLEAN) << 8) | message[14] & CLEAN));
        m.setMss((short) (((message[15] & CLEAN) << 8) | message[16] & CLEAN));
        m.setTime(((long) (message[17] & CLEAN) << 56) | ((long) (message[18] & CLEAN) << 48) | ((long) (message[19] & CLEAN) << 40)
                | ((long) (message[20] & CLEAN) << 32) | ((long) (message[21] & CLEAN) << 24) | ((long) (message[22] & CLEAN) << 16) | ((long) (message[23] & CLEAN) << 8) | ((long) message[24] & CLEAN));

        byte[] c = new byte[m.getContentLength()];
        System.arraycopy(message, HEAD_LENGTH, c, 0, m.getContentLength());
        m.setContent(c);

        logger.debug("接受到报文，长度:{},报文序号{}", m.contentLength, m.getRank());
        return m;
    }

    void setContent(byte[] content) {
        this.contentLength = (short) content.length;
        this.content = content;
    }

    //TODO yh 检查过时消息
    //TODO hl 可能需要新增一个参数确定是哪个分块

}
