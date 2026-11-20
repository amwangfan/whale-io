package com.turboio.addon;

import java.util.Arrays;
import java.util.Map;

/** The key-name probe must never leak a value: strings, numbers, list elements, non-API cache keys. */
public final class OfficialSummaryProbeTest {
    static int n;
    static void ok(boolean b, String why) { n++; if (!b) throw new AssertionError("probe " + n + " " + why); }
    public static void main(String[] a) throws Exception {
        String secretSummary = "SECRET-SUMMARY-TEXT-今天和张三讨论了工资", secretTitle = "SECRET-TITLE", secretKey = "https://evil.example/private?token=SECRET-TOKEN";
        Map<String, Object> event = CeLifelogTest.m("createdAt", "2026-10-23T08:00:00Z", "response", CeLifelogTest.m("code", 0L, "data", CeLifelogTest.m(
            "title", secretTitle, "summary", secretSummary, "keyPoints", Arrays.asList("SECRET-POINT-1", "SECRET-POINT-2"), "speakerCount", 3L,
            "transcriptionContents", Arrays.asList(CeLifelogTest.m("clips", Arrays.asList(CeLifelogTest.m("content", "SECRET-CLIP", "startTime", "1792990000000")))),
            "weird key with spaces & secret", "x")));
        byte[] hive = CeLifelogTest.cat(
            CeLifelogTest.frame("https://xr-api-p.rayneo.cn/lifelog/api/v1/events/123456/full?lang=zh&secretParam=SECRET-PARAM-VALUE", event, false),
            CeLifelogTest.frame("https://xr-api-p.rayneo.cn/lifelog/api/v1/events/987654/full?lang=zh", event, false),
            CeLifelogTest.frame(secretKey, CeLifelogTest.m("a", "b"), false),
            CeLifelogTest.frame("user_pref_SECRET-NON-URL-KEY", "SECRET-VALUE", false));
        String report = OfficialSummaryProbe.report(hive);
        for (String leak : new String[] {"SECRET-SUMMARY", "SECRET-TITLE", "SECRET-POINT", "SECRET-CLIP", "SECRET-TOKEN", "SECRET-PARAM-VALUE", "SECRET-NON-URL", "SECRET-VALUE", "evil.example", "123456", "987654", "1792990000000", "张三", "weird key"})
            ok(!report.contains(leak), "report must not contain " + leak);
        ok(report.contains("xr-api-p.rayneo.cn/lifelog/api/v1/events/{n}/full?lang&secretParam"), "route shape: ids replaced, only query parameter names kept");
        ok(report.contains("×1") || report.contains("×2"), "route counts present");
        ok(report.contains("summary:str(<100)") && report.contains("title:str(<100)") && report.contains("keyPoints:[2×str(<100)]") && report.contains("speakerCount:num"), "key names with types and length buckets");
        ok(report.contains("transcriptionContents:[1×{clips:[1×{content:str(<100),startTime:str(<100)}]}]"), "nested key names");
        ok(report.contains("<key>") && report.contains("other_cache_keys=2"), "non-identifier keys masked; non-API cache keys only counted");
        ok(OfficialSummaryProbe.route("http://x/y") == null && OfficialSummaryProbe.route(42) == null, "non-https and non-string keys are not routes");
        System.out.println("Official summary probe tests passed (" + n + " checks).");
    }
}
