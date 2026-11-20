import com.turboio.addon.TransferGate;
import static com.turboio.addon.TransferGate.State.*;
public class TransferGateTest {
    static int count;static void check(boolean b){count++;if(!b)throw new AssertionError(count);}
    static TransferGate gate(){TransferGate g=new TransferGate();g.begin("peer","turbo-focus.tfp",42,9,100,1000);return g;}
    public static void main(String[] args){
        TransferGate g=gate();check(g.state()==SUBMITTING);check(!g.mayDeleteSpool());
        check(g.acknowledgement("peer",42,9,0));check(g.state()==SUBMITTING);
        g.submitted("native");check(g.state()==WAITING);
        check(!g.fileResult("other","native","turbo-focus.tfp",true,true));
        check(!g.fileResult("peer","wrong","turbo-focus.tfp",true,true));
        check(!g.fileResult("peer","native","turbo-app.tax",true,true));
        check(!g.fileResult("peer","native","turbo-focus.tfp",false,true));
        check(g.fileResult("peer","native","turbo-focus.tfp",true,true));check(g.state()==CONFIRMED);check(g.mayDeleteSpool());
        check(!g.acknowledgement("peer",42,9,4));check(g.state()==CONFIRMED);
        g=gate();g.submitted("native");g.fileResult("peer","native","turbo-focus.tfp",true,true);check(g.state()==WAITING);
        check(!g.acknowledgement("peer",41,9,0));check(!g.acknowledgement("peer",42,8,0));
        check(g.acknowledgement("peer",42,9,4));check(g.state()==REJECTED&&g.result()==4);
        g=gate();g.submitted("native");g.tick("peer",1100);check(g.state()==UNCERTAIN);check(!g.mayDeleteSpool());
        check(!g.fileResult("peer","native","turbo-focus.tfp",true,true));
        g=gate();g.tick("other",101);check(g.state()==UNCERTAIN);
        g=gate();g.submitted(null);check(g.state()==UNCERTAIN);
        g=gate();g.submitted("native");g.submitted("different");check(g.state()==UNCERTAIN);
        g=gate();g.acknowledgement("peer",42,9,0);g.acknowledgement("peer",42,9,1);check(g.state()==UNCERTAIN);
        g=gate();g.submitted("native");g.fileResult("peer","native","turbo-focus.tfp",true,false);check(g.state()==FAILED&&!g.mayDeleteSpool());
        boolean rejected=false;g=gate();try{g.begin("peer","turbo-app.tax",43,0,0,100);}catch(IllegalStateException e){rejected=true;}check(rejected);
        // V5 T6 fast bulk: the shell self-acks each bulk packet right after send() (merged replies are pure
        // accounting) — ack(0) lands while SUBMITTING, the file terminal then settles CONFIRMED. Five chunk
        // cycles on the greeting lane, every one CONFIRMED + spool-deletable → settle() never quarantines.
        for(int i=0;i<5;i++){TransferGate b=new TransferGate();b.begin("peer","turbo-greeting.tdg",100+i,0x47525400L,500,5000);
            check(b.acknowledgement("peer",100+i,0x47525400L,0));
            b.submitted("native");
            check(b.fileResult("peer","native","turbo-greeting.tdg",true,true));
            check(b.state()==CONFIRMED&&b.mayDeleteSpool());}
        // Gate sharing unchanged: a second begin while the lane is busy is refused, fast lane or not.
        TransferGate busyGate=new TransferGate();busyGate.begin("peer","turbo-greeting.tdg",200,0x47525400L,100,5000);
        boolean refused=false;try{busyGate.begin("peer","turbo-display.tdp",201,7392,100,5000);}catch(IllegalStateException e){refused=true;}check(refused);
        System.out.println("Transfer gate: "+count+" assertions passed");
    }
}
