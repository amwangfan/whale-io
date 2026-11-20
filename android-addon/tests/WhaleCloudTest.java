import com.turboio.addon.WhalePayload;
import java.lang.reflect.Method;

/** whale-core request body contract tests: exact JSON shape, escaping, mode mapping, id omission rules,
 *  and the privacy invariant that request bodies carry TEXT ONLY (no audio/pcm/base64 fields ever). */
public final class WhaleCloudTest {
    private static int checks;
    private static void check(boolean v) { if (!v) throw new AssertionError("check " + checks); checks++; }
    public static void main(String[] args) throws Exception {
        // chat: shape + default companion + session echo
        check("{\"text\":\"你好\",\"mode\":\"companion\"}".equals(WhalePayload.chatBody("你好", null, "")));
        check("{\"text\":\"你好\",\"mode\":\"quick\",\"sessionId\":\"s1\"}".equals(WhalePayload.chatBody("你好", "quick", "s1")));
        check("{\"text\":\"hi\",\"mode\":\"companion\"}".equals(WhalePayload.chatBody("hi", "QUICK", null)));
        // agent: blank text omitted (confirm re-run), all ids present when set
        check("{\"sessionId\":\"s1\",\"confirmId\":\"c9\"}".equals(WhalePayload.agentBody("  ", "s1", "c9")));
        check("{\"text\":\"列出文件\"}".equals(WhalePayload.agentBody("列出文件", null, null)));
        check("{}".equals(WhalePayload.agentBody(null, "", null)));
        // qq: minimal
        check("{\"text\":\"晚上好\"}".equals(WhalePayload.qqBody("晚上好")));
        // escaping: quotes, backslash, newline; C0 becomes space
        check("{\"text\":\"a\\\"b\\\\c\\nd\"}".equals(WhalePayload.qqBody("a\"b\\c\nd")));
        check("{\"text\":\"a b\"}".equals(WhalePayload.qqBody("ab")));
        // privacy invariant: builders never emit audio-bearing fields
        for (String m : new String[]{"chatBody", "agentBody", "qqBody"}) {
            for (Method mm : WhalePayload.class.getDeclaredMethods()) if (mm.getName().equals(m)) check(mm.getParameterCount() <= 3);
        }
        String sample = WhalePayload.chatBody("播放音乐 并 记录这段话", "companion", "s-uuid-1234");
        check(!sample.contains("audio") && !sample.contains("pcm") && !sample.contains("base64") && !sample.contains("wav"));
        // size: a 1KB Chinese request stays far under the 60 KiB body cap
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 340; i++) big.append("法普妲");
        byte[] utf8 = WhalePayload.chatBody(big.toString(), "quick", "s").getBytes("UTF-8");
        check(utf8.length > 3000 && utf8.length < 60000);
        System.out.println("PASS " + checks + " whalecloud payload checks");
    }
}
