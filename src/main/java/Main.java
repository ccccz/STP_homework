import java.io.IOException;
import java.util.Calendar;

/**
 * @author DW
 * @date 2019/5/5
 * 不需要修改，暂时可以使用
 */
public class Main {
    public static void main(String[] args) throws IOException {
        Message m = new Message();
        m.setSrcPort(65535);
        m.setDisPort(1025);
        m.setRank(500);
        m.setSeq(5999999);
        m.setAck(true);
        m.setFin(true);
        m.setWindow((short) 3532);
        m.setMss((short) 2344);
        m.setTime(Calendar.getInstance().getTimeInMillis());
        m.setContent(new byte[0]);
        System.out.println(Message.deMessage(m.enMessage()));
    }
}


