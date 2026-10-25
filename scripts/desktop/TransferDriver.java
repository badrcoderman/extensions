import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class TransferDriver {
    static Object headers;
    static Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        try { return target.getClass().getMethod(method, types).invoke(target, args); }
        catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
    }
    static Object request(Object client, String url) throws Exception {
        Object builder=Class.forName("okhttp3.Request$Builder").getConstructor().newInstance();
        invoke(builder,"url",new Class[]{String.class},url);
        if (headers!=null) invoke(builder,"headers",new Class[]{Class.forName("okhttp3.Headers")},headers);
        Object req=invoke(builder,"build",new Class[]{});
        return invoke(client,"newCall",new Class[]{Class.forName("okhttp3.Request")},req);
    }
    static byte[] get(Object client, String url) throws Exception {
        Object call=request(client,url), response=invoke(call,"execute",new Class[]{});
        try {
            int code=(int)invoke(response,"code",new Class[]{});
            if(code<200||code>=300) throw new java.io.IOException("Image HTTP status "+code);
            return (byte[])invoke(invoke(response,"body",new Class[]{}),"bytes",new Class[]{});
        }
        finally {((AutoCloseable)response).close();}
    }
    public static void main(String[] args) throws Exception {
        Object client=Class.forName("okhttp3.OkHttpClient").getConstructor().newInstance();
        Object headersBuilder=Class.forName("okhttp3.Headers$Builder").getConstructor().newInstance();
        invoke(headersBuilder,"add",new Class[]{String.class,String.class},"User-Agent","Mozilla/5.0");
        invoke(headersBuilder,"add",new Class[]{String.class,String.class},"Referer","https://procomic.pro/");
        if (!args[0].equals("stitch") && args.length>2) invoke(headersBuilder,"add",new Class[]{String.class,String.class},"Cookie",Files.readString(Path.of(args[2])).strip());
        headers=invoke(headersBuilder,"build",new Class[]{});
        if(args[0].equals("stitch")) {
            Object url=Class.forName("okhttp3.HttpUrl").getMethod("get",String.class).invoke(null,"http://procomic.pieces/#%7B%22chapterId%22:123,%22plan%22:%7B%22token%22:%22fixture%22%7D%7D");
            Object parent=request(client,args[1]+"/parent");
            Class<?> helper=Class.forName("eu.kanade.tachiyomi.extension.ar.shared.ProComicStitcher");
            Object result=helper.getMethod("stitch",Class.forName("okhttp3.OkHttpClient"),Class.forName("okhttp3.Headers"),String.class,Class.forName("okhttp3.HttpUrl"),Class.forName("okhttp3.Call")).invoke(null,client,headers,args[1],url,parent);
            Files.write(Path.of(args[2]),(byte[])invoke(result,"readByteArray",new Class[]{}));
            System.out.println("Stitch completed");
            return;
        }
        List<String> urls=Files.readAllLines(Path.of(args[1]));
        get(client,urls.get(0)); // Warm the shared connection before timing.
        List<String> hashes=new ArrayList<>();long bytes=0;
        Object batch=null;
        long start=System.nanoTime();
        if(args[0].equals("parallel")) {
            Class<?> c=Class.forName("eu.kanade.tachiyomi.extension.ar.shared.PieceDownloads");
            batch=c.getConstructor(Class.forName("okhttp3.OkHttpClient"),Class.forName("okhttp3.Headers"),List.class,Class.forName("okhttp3.Call")).newInstance(client,headers,urls,request(client,urls.get(0)));
        }
        try {
            for(int i=0;i<urls.size();i++) {
                byte[] data=batch==null?get(client,urls.get(i)):(byte[])invoke(batch,"bytes",new Class[]{int.class},i);
                bytes+=data.length;hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
            }
        } finally {if(batch!=null)((AutoCloseable)batch).close();}
        System.out.println("{\"mode\":\""+args[0]+"\",\"milliseconds\":"+((System.nanoTime()-start)/1000000)+",\"bytes\":"+bytes+",\"hashes\":[\""+String.join("\",\"",hashes)+"\"]}");
    }
}
