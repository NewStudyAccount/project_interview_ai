import java.nio.ByteBuffer;

public class Exp32 {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "info";
        Runtime rt = Runtime.getRuntime();
        switch (mode) {
            case "info": {
                System.out.println("maxMemory=" + rt.maxMemory()
                        + " (" + (rt.maxMemory() >> 20) + " MiB)");
                System.out.println("totalMemory=" + rt.totalMemory());
                System.out.println("availableProcessors=" + rt.availableProcessors());
                System.out.println("directLimit~maxHeap(if MaxDirectMemorySize=0)");
                ByteBuffer b = ByteBuffer.allocateDirect(16 * 1024 * 1024);
                System.out.println("direct16M ok " + b.isDirect());
                break;
            }
            case "apc": {
                System.out.println("availableProcessors=" + rt.availableProcessors());
                break;
            }
            case "oom": {
                System.err.println("start leaking heap...");
                java.util.List<byte[]> keep = new java.util.ArrayList<>();
                for (int i = 0; i < 100000; i++) {
                    keep.add(new byte[1024 * 1024]); // 1 MiB each
                }
                System.out.println("never reach here " + keep.size());
                break;
            }
            case "direct": {
                System.err.println("start leaking direct...");
                java.util.List<ByteBuffer> keep = new java.util.ArrayList<>();
                for (int i = 0; i < 10000; i++) {
                    keep.add(ByteBuffer.allocateDirect(16 * 1024 * 1024));
                }
                System.out.println("never reach here " + keep.size());
                break;
            }
            default:
                System.out.println("unknown mode");
        }
    }
}
