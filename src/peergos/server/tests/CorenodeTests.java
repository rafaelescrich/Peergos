package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.corenode.UsernameValidator;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.merklebtree.*;
import peergos.shared.util.*;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(Parameterized.class)
public class CorenodeTests {

    public static int RANDOM_SEED = 666;
    private final NetworkAccess network;

    private static Random random = new Random(RANDOM_SEED);

    public CorenodeTests(String useIPFS, Random r) throws Exception {
        int webPort = 9000 + r.nextInt(1000);
        int corePort = 10000 + r.nextInt(1000);
        Args args = Args.parse(new String[]{"useIPFS", ""+useIPFS.equals("IPFS"), "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Start.LOCAL.main(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        Random r = new Random(1234);
        return Arrays.asList(new Object[][] {
//                {"IPFS", r},
                {"RAM", r}
        });
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    @Test
    public void writeThroughput() throws Exception {
        Crypto crypto = Crypto.initJava();
        ForkJoinPool pool = new ForkJoinPool(10);

        List<Future<Long>> worstLatencies = new ArrayList<>();
        for (int t = 0; t < 10; t++)
            worstLatencies.add(pool.submit(() -> {
                SigningKeyPair owner = SigningKeyPair.random(crypto.random, crypto.signer);
                SigningKeyPair writer = SigningKeyPair.random(crypto.random, crypto.signer);
                PublicKeyHash ownerHash = network.dhtClient.putSigningKey(owner.publicSigningKey).get();
                PublicKeyHash writerHash = network.dhtClient.putSigningKey(writer.publicSigningKey).get();

                byte[] data = new byte[10];

                MaybeMultihash current = MaybeMultihash.empty();
                long t1 = System.currentTimeMillis();
                int iterations = 100;
                long maxLatency = 0;
                for (int i = 0; i < iterations; i++) {
                    random.nextBytes(data);
                    Cid cid = RAMStorage.hashToCid(data, true);
                    HashCasPair cas = new HashCasPair(current, MaybeMultihash.of(cid));
                    byte[] signed = writer.signMessage(cas.serialize());
                    try {
                        long t3 = System.currentTimeMillis();
                        network.mutable.setPointer(ownerHash, writerHash, signed).get();
                        long latency = System.currentTimeMillis() - t3;
                        if (latency > maxLatency)
                            maxLatency = latency;
                        current = MaybeMultihash.of(cid);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                long t2 = System.currentTimeMillis();
                System.out.printf("%d iterations took %d mS\n", iterations, t2 - t1);
                return maxLatency;
            }));
        long worstLatency = worstLatencies.stream().mapToLong(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return Long.MIN_VALUE;
            }
        }).max().getAsLong();
        System.out.println("Worst Latency: " + worstLatency);
        Assert.assertTrue("Worst latency < 1 second: " + worstLatency, worstLatency < 2000);
        pool.awaitQuiescence(5, TimeUnit.MINUTES);
    }

    @Test
    public void isValidUsernameTest() {
        List<String> areValid = Arrays.asList("chris", "super_califragilistic_ex", "z", "c.h_r.i.s");

        List<String> areNotValid = Arrays.asList(
                " ",
                "super_califragilistic_expilalidocious",
                "\n",
                "\r",
                "_hello",
                "hello.",
                "\b0");

        areValid.forEach(username -> Assert.assertTrue(username + " is valid", UsernameValidator.isValidUsername(username)));
        areNotValid.forEach(username -> Assert.assertFalse(username +" is not valid", UsernameValidator.isValidUsername(username)));
    }
}
