package com.turboio.addon;

/** FrameRetry §7 policy: bounded attempts, isolation, breaker with cooldown and reconnect. */
public final class FrameRetryTest {
    static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("retry "+n+" "+why);}
    public static void main(String[] a){
        FrameRetry r=new FrameRetry();
        ok(r.begin(0),"first frame");
        ok(r.attempt()==1,"attempt 1");
        ok(r.retry()&&r.attempt()==2,"second attempt");
        ok(r.retry()&&r.attempt()==3,"third attempt");
        ok(!r.retry(),"bounded at 3");
        r.drop(10);ok(r.totalDropped()==1&&r.consecutiveDrops()==1,"dropped and isolated");
        ok(r.begin(11),"next push unaffected");
        ok(r.retry(),"retry of the new frame");
        r.success();ok(r.consecutiveDrops()==0&&r.attempt()==0,"success resets");
        // Breaker: three consecutive drops open it; pushes refused during cooldown; reconnect reopens at once.
        for(int i=0;i<3;i++){ok(r.begin(100+i),"breaker frame "+i);while(r.retry()){}r.drop(100+i);}
        ok(r.open()&&r.totalDropped()==4,"breaker open after 3 consecutive drops");
        ok(!r.begin(120),"refused while cooling down");
        r.reconnect();ok(r.begin(121),"reconnect reopens immediately");
        while(r.retry()){}r.drop(130);
        for(int i=0;i<2;i++){ok(r.begin(131+i),"post-drop frame");while(r.retry()){}r.drop(131+i);}
        ok(r.open(),"breaker reopens");
        ok(!r.begin(140),"still refused");
        ok(r.begin(200),"cooldown elapsed allows one probe");
        r.success();ok(!r.open(),"probe success closes the breaker");
        System.out.println("FrameRetryTest: "+n+" checks; bounded attempts, isolation, breaker cooldown/reconnect");
    }
}
