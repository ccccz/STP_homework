import lombok.Data;

/**
 * @author DW
 * @date 2019/5/5
 * 不需要修改，暂时可以使用
 */
@Data
public class Main {

    public static void main(String[] args) throws InterruptedException {
        (new Thread(() -> {
            Receiver.main(new String[]{"receive.txt", "127.0.0.1", "6666", "1000", "1024", "10240"});
        })).start();

        (new Thread(() -> {
            Sender.main(new String[]{"file.txt", "127.0.0.1", "6666", "0.4", "1077", "1000", "0.3", "5678", "1024", "10240", "15"});
        })).start();

    }


}


