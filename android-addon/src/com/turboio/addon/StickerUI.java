package com.turboio.addon;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;

/** 鲸鱼贴纸: two whale-girl sets (Q-version and mature proportions, community redraws) plus the earlier
 * self-made little-whale set. Each sticker is a transparent 512 px phone view + a 480x480 black-background,
 * high-contrast glasses view. Viewing and exporting to Download/TurboIO only. Pushing an image to the glasses
 * over a stock-firmware channel is unverified (待核): the official 1.0.20 host exposes no confirmed
 * phone-to-glasses image message (MARS_PHOTO_TRANSFER is glasses-to-phone), so nothing is sent to the glasses
 * from here. */
final class StickerUI {
    static final String[] IDS = {"hello", "thinking", "sleepy", "thumbsup", "confused", "working"};
    static final String[] NAMES = {"打招呼", "思考", "困倦", "点赞", "疑惑", "干活"};
    static final String CREDIT =
            "出处：https://github.com/TreapGoGo/deepseek-whale-girl\n"
            + "https://fisharchive.cc\n"
            + "原作：https://space.bilibili.com/4456176\n"
            + "二次设计：https://space.bilibili.com/4168597\n"
            + "点开一套可预览、导出到 Download/TurboIO，或推送到眼镜显示（问候通道：自动开页，眼镜在忙则排队稍后送达；本地处理，不上云）。";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Set {
        final String prefix;
        final String title;
        final String blurb;
        Set(String prefix, String title, String blurb) {
            this.prefix = prefix; this.title = title; this.blurb = blurb;
        }
    }

    static final Set[] SETS = {
        new Set("wg-chibi-", "Q 版鲸鱼娘", "三头身、圆润。打招呼、思考、困倦、点赞、疑惑、干活，共 6 张。"),
        new Set("wg-full-", "成熟比例鲸鱼娘", "腿长、腰线清晰、裙更长、过膝袜。同一套 6 张表情。"),
        new Set("ds-whale-", "小鲸鱼", "此前自制的 DeepSeek 风格小鲸鱼，仍可查看和导出。")
    };

    private StickerUI() {}

    static Bitmap load(Activity a, Set set, String id, boolean glasses) {
        try (InputStream in = a.getAssets().open("turboio/stickers/" + set.prefix + id + (glasses ? "-glasses" : "") + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) { return null; }
    }

    static void show(Activity a) {
        EditorialUI.Screen s = new EditorialUI.Screen(a, "鲸鱼贴纸");
        LinearLayout b = s.body;
        b.addView(EditorialUI.text(a, CREDIT, 13, EditorialUI.MUTED));
        TextView pushState = EditorialUI.text(a, GreetingBridge.status(), 13, EditorialUI.LIME);
        GreetingBridge.listen(() -> pushState.setText(GreetingBridge.status()));
        s.dialog.setOnDismissListener(d -> GreetingBridge.listen(null));
        EditorialUI.button(a, b, "添加手机里的图片并推送到眼镜…", false, () -> pickAndPush(a, pushState));
        for (int i = 0; i < SETS.length; i++) {
            final Set set = SETS[i];
            LinearLayout card = EditorialUI.column(a);
            card.setBackground(EditorialUI.shape(a, EditorialUI.SURFACE, 24));
            card.setPadding(EditorialUI.dp(a, 12), EditorialUI.dp(a, 12), EditorialUI.dp(a, 12), EditorialUI.dp(a, 12));
            LinearLayout row = new LinearLayout(a);
            ImageView image = new ImageView(a);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(load(a, set, "hello", false));
            image.setContentDescription(set.title + " 预览");
            row.addView(image, new LinearLayout.LayoutParams(EditorialUI.dp(a, 108), EditorialUI.dp(a, 108)));
            LinearLayout text = EditorialUI.column(a);
            text.setPadding(EditorialUI.dp(a, 12), EditorialUI.dp(a, 4), 0, 0);
            TextView title = EditorialUI.text(a, set.title + "  ↗", 16, EditorialUI.INK);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            text.addView(title);
            text.addView(EditorialUI.text(a, set.blurb, 13, EditorialUI.MUTED));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(row);
            card.setOnClickListener(v -> showSet(a, set));
            card.setFocusable(true);
            card.setContentDescription(set.title);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = EditorialUI.dp(a, 12);
            b.addView(card, p);
        }
    }

    static void showSet(Activity a, Set set) {
        EditorialUI.Screen s = new EditorialUI.Screen(a, set.title);
        LinearLayout b = s.body;
        b.addView(EditorialUI.text(a, set.blurb + "\n点开可查看，并把“透明 PNG”或“眼镜版（黑底高亮）”保存到 Download/TurboIO。", 13, EditorialUI.MUTED));
        for (int row = 0; row < 3; row++) {
            LinearLayout pair = new LinearLayout(a);
            for (int col = 0; col < 2; col++) {
                final int i = row * 2 + col;
                LinearLayout cell = EditorialUI.column(a);
                cell.setBackground(EditorialUI.shape(a, EditorialUI.SURFACE, 24));
                cell.setPadding(EditorialUI.dp(a, 8), EditorialUI.dp(a, 8), EditorialUI.dp(a, 8), EditorialUI.dp(a, 10));
                ImageView image = new ImageView(a);
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(load(a, set, IDS[i], false));
                image.setContentDescription(set.title + " " + NAMES[i]);
                cell.addView(image, new LinearLayout.LayoutParams(-1, EditorialUI.dp(a, 140)));
                TextView label = EditorialUI.text(a, NAMES[i], 14, EditorialUI.INK);
                label.setGravity(android.view.Gravity.CENTER);
                cell.addView(label);
                cell.setOnClickListener(v -> detail(a, set, i));
                cell.setFocusable(true);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                p.topMargin = EditorialUI.dp(a, 12);
                if (col == 0) p.rightMargin = EditorialUI.dp(a, 12);
                pair.addView(cell, p);
            }
            b.addView(pair);
        }
    }

    private static void detail(Activity a, Set set, int i) {
        EditorialUI.Screen s = new EditorialUI.Screen(a, set.title + " · " + NAMES[i]);
        LinearLayout b = s.body;
        ImageView image = new ImageView(a);
        image.setAdjustViewBounds(true);
        image.setImageBitmap(load(a, set, IDS[i], true));
        image.setBackgroundColor(0xff000000);
        image.setContentDescription("眼镜版预览（黑底高亮）");
        b.addView(image, new LinearLayout.LayoutParams(-1, EditorialUI.dp(a, 320)));
        b.addView(EditorialUI.text(a, "上图为眼镜版（黑底高亮，480×480）。黑色在眼镜上等于透明。", 13, EditorialUI.MUTED));
        TextView note = EditorialUI.text(a, "", 13, EditorialUI.LIME);
        EditorialUI.button(a, b, "保存透明 PNG（手机用，512 px）", true, () -> export(a, set, i, false, note));
        EditorialUI.button(a, b, "保存眼镜版（黑底高亮，480×480）", false, () -> export(a, set, i, true, note));
        EditorialUI.button(a, b, "推送到眼镜显示", false, () -> pushSticker(a, set, i, note));
        b.addView(note);
    }

    /** Push one sticker to the glasses over the greeting channel: fit the black-background glasses version into
     *  the 512×128 gray frame and hand to GreetingBridge with the "sticker" content key (NOTIFY_DEFER). */
    private static void pushSticker(Activity a, Set set, int i, TextView note) {
        Bitmap glasses = load(a, set, IDS[i], true);
        if (glasses == null) { note.setText("贴纸资源缺失，未推送。"); return; }
        byte[] frame = toFrame(glasses);
        glasses.recycle();
        note.setText("推送中…（占页则排队稍后送达；状态见本页与问候页）");
        GreetingBridge.push(frame, "sticker", "贴纸");
    }

    /** Fit any bitmap into the 512×128 grayscale frame (the salvaged image-lab pipeline, local only).
     *  mode: 0 direct luma / 1 16-level pre-quantized (default) / 2 Bayer dithered (GRAYMAP §4 A/B). */
    static byte[] toFrame(Bitmap src) { return toFrame(src, 1); }
    static byte[] toFrame(Bitmap src, int mode) {
        android.graphics.Bitmap canvas = android.graphics.Bitmap.createBitmap(Tdp.WIDTH, Tdp.HEIGHT, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(canvas); c.drawColor(0xff000000);
        int[] r = Timg.fit(src.getWidth(), src.getHeight());
        c.drawBitmap(src, null, new android.graphics.Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        int[] argb = new int[Tdp.PIXELS]; canvas.getPixels(argb, 0, Tdp.WIDTH, 0, 0, Tdp.WIDTH, Tdp.HEIGHT); canvas.recycle();
        return mode==0?Timg.luma(argb):mode==2?Timg.lumaQ16Dither(argb):Timg.lumaQ16(argb);
    }

    /** Pick a phone image (PNG/JPEG/HEIC, ≤16MP/20MiB), fit to the 512×128 frame, push. Salvaged from the
     *  image-upload lab (removed 2026-11-17); the decode runs on a worker, the push on the greeting channel. */
    private static void pickAndPush(Activity a, TextView note) {
        DocumentPicker.pick(a, 20 * 1024 * 1024, bytes -> new Thread(() -> {
            byte[] frame = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 16_000_000L) throw new IllegalArgumentException();
                int sample = 1; while (o.outWidth / (sample * 2) >= Tdp.WIDTH && o.outHeight / (sample * 2) >= Tdp.HEIGHT) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = sample;
                Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
                if (src == null) throw new IllegalArgumentException();
                frame = toFrame(src); src.recycle();
            } catch (Throwable ignored) {}
            final byte[] f = frame;
            MAIN.post(() -> { if (f == null) { note.setText("选图失败：只支持单帧 PNG/JPEG/HEIC，最多 16MP、20 MiB。"); return; }
                note.setText("推送中…（占页则排队稍后送达；状态见本页与问候页）");
                GreetingBridge.push(f, "sticker", "贴纸"); });
        }, "turboio-sticker-pick").start());
    }

    private static void export(Activity a, Set set, int i, boolean glasses, TextView note) {
        if (Build.VERSION.SDK_INT < 29) { note.setText("需要 Android 10+（MediaStore.Downloads）"); return; }
        final String name = set.prefix + IDS[i] + (glasses ? "-glasses" : "") + ".png";
        new Thread(() -> {
            Uri uri = null; String result;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name); v.put(MediaStore.Downloads.MIME_TYPE, "image/png");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TurboIO"); v.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("insert");
                try (InputStream in = a.getAssets().open("turboio/stickers/" + name); OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("stream");
                    byte[] block = new byte[8192]; int n; while ((n = in.read(block)) != -1) out.write(block, 0, n);
                }
                v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0); a.getContentResolver().update(uri, v, null, null);
                result = "已保存：Download/TurboIO/" + name;
            } catch (Exception e) {
                if (uri != null) try { a.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                result = "保存失败（" + e.getClass().getSimpleName() + "）";
            }
            final String text = result;
            MAIN.post(() -> note.setText(text));
        }, "turboio-sticker-export").start();
    }
}
