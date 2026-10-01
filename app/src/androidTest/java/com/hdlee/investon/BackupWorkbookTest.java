package com.hdlee.investon;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.zip.*;

public class BackupWorkbookTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    @Override public void onStart() { new Thread(()-> { Bundle result=new Bundle();try {
        testRoundTrip();testEmptyBackup();testRejectNonWorkbook();testEditedExcelSharedStringsAndOrder();testLiveEtfSearch();
        result.putString("stream","\nOK (5 tests)\n");finish(Activity.RESULT_OK,result);
    } catch(Throwable e) { StringWriter text=new StringWriter();e.printStackTrace(new PrintWriter(text));result.putString("stream","\nFAILURES\n"+text);finish(Activity.RESULT_CANCELED,result); } },"native-backup-tests").start(); }
    Instrumentation getInstrumentation() { return this; }
    static void fail(String text) { throw new AssertionError(text); }
    static void assertTrue(boolean value) { assertTrue("Expected true",value); }
    static void assertTrue(String text,boolean value) { if(!value)fail(text); }
    static void assertFalse(boolean value) { assertTrue(!value); }
    static void assertEquals(String a,String b) { if(!a.equals(b))fail(a+" != "+b); }
    static void assertEquals(int a,int b) { if(a!=b)fail(a+" != "+b); }
    static void assertEquals(double a,double b,double delta) { if(Math.abs(a-b)>delta)fail(a+" != "+b); }
    JSONObject sample() throws Exception {
        return new JSONObject("{\"version\":1,\"watch\":[{\"symbol\":\"069500.KS\",\"name\":\"KODEX 200\",\"currency\":\"KRW\",\"type\":\"ETF\"},{\"symbol\":\"005930.KS\",\"name\":\"삼성전자\",\"currency\":\"KRW\",\"type\":\"EQUITY\"}],\"accounts\":[\"퇴직연금\",\"ISA & 투자\"],\"holdings\":[{\"symbol\":\"069500.KS\",\"name\":\"KODEX 200\",\"currency\":\"KRW\",\"type\":\"ETF\",\"account\":\"퇴직연금\",\"quantity\":1.125,\"cost\":38000.5,\"id\":\"holding-1\"}],\"rules\":[{\"symbol\":\"069500.KS\",\"name\":\"KODEX 200\",\"currency\":\"KRW\",\"type\":\"ETF\",\"target\":40000,\"direction\":\"above\",\"active\":false,\"id\":\"rule-1\"}]}");
    }
    public void testRoundTrip() throws Exception {
        JSONObject original=sample();ByteArrayOutputStream bytes=new ByteArrayOutputStream();BackupWorkbook.write(original,bytes);
        JSONObject restored=BackupWorkbook.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("069500.KS",restored.getJSONArray("watch").getJSONObject(0).getString("symbol"));
        assertEquals("005930.KS",restored.getJSONArray("watch").getJSONObject(1).getString("symbol"));
        assertEquals("ISA & 투자",restored.getJSONArray("accounts").getString(1));
        JSONObject h=restored.getJSONArray("holdings").getJSONObject(0);assertEquals(1.125,h.getDouble("quantity"),0);assertEquals(38000.5,h.getDouble("cost"),0);assertEquals("holding-1",h.getString("id"));
        assertFalse(restored.getJSONArray("rules").getJSONObject(0).getBoolean("active"));
        File f=new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"native-backup-test.xlsx");try(OutputStream out=new FileOutputStream(f)){out.write(bytes.toByteArray());}
    }
    public void testEmptyBackup() throws Exception {
        JSONObject empty=new JSONObject("{\"version\":1,\"watch\":[],\"accounts\":[],\"holdings\":[],\"rules\":[]}");ByteArrayOutputStream bytes=new ByteArrayOutputStream();BackupWorkbook.write(empty,bytes);assertEquals(0,BackupWorkbook.read(new ByteArrayInputStream(bytes.toByteArray())).getJSONArray("watch").length());
    }
    public void testRejectNonWorkbook() throws Exception {
        try{BackupWorkbook.read(new ByteArrayInputStream("{}".getBytes()));fail("Must reject JSON as Excel");}catch(IOException expected){}
    }
    public void testEditedExcelSharedStringsAndOrder() throws Exception {
        ByteArrayOutputStream source=new ByteArrayOutputStream();BackupWorkbook.write(sample(),source);ByteArrayOutputStream edited=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(source.toByteArray()));ZipOutputStream out=new ZipOutputStream(edited)) { ZipEntry e;while((e=in.getNextEntry())!=null){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);String text=b.toString("UTF-8");
            if(e.getName().equals("xl/worksheets/sheet1.xml"))text=text.replace("r=\"A2\" s=\"2\"><v>1</v>","r=\"A2\" s=\"2\"><v>2</v>").replace("r=\"A3\" s=\"2\"><v>2</v>","r=\"A3\" s=\"2\"><v>1</v>").replace("t=\"inlineStr\"><is><t xml:space=\"preserve\">KODEX 200</t></is>","t=\"s\"><v>0</v>");
            BackupWorkbook.entry(out,e.getName(),text);
        }BackupWorkbook.entry(out,"xl/sharedStrings.xml","<sst xmlns=\""+BackupWorkbook.NS+"\"><si><t>KODEX 200</t></si></sst>");}
        JSONObject restored=BackupWorkbook.read(new ByteArrayInputStream(edited.toByteArray()));assertEquals("005930.KS",restored.getJSONArray("watch").getJSONObject(0).getString("symbol"));assertEquals("KODEX 200",restored.getJSONArray("watch").getJSONObject(1).getString("name"));
    }
    public void testLiveEtfSearch() throws Exception {
        MarketClient client=new MarketClient(getInstrumentation().getTargetContext());JSONArray result=client.search("200");assertTrue("200 must match multiple domestic ETFs",result.length()>2);boolean kodex=false;
        File proof=new File(getTargetContext().getExternalFilesDir(null),"native-search-test.json");try(OutputStream out=new FileOutputStream(proof)){out.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        for(int i=0;i<result.length();i++){JSONObject item=result.getJSONObject(i);assertEquals("ETF",item.getString("type"));assertTrue("Expected name or code substring: "+item,item.getString("name").contains("200")||item.getString("symbol").contains("200"));if(item.getString("symbol").equals("069500.KS")){kodex=true;assertTrue(item.getString("name").contains("KODEX"));}}
        assertTrue("KODEX 200 must be found",kodex);assertTrue(client.search("KODEX200").length()>0);
        JSONArray catalog=client.etfs();boolean korean=false;for(int i=0;i<catalog.length();i++){String name=catalog.getJSONObject(i).getString("name");assertFalse(name.contains("\uFFFD"));if(name.matches(".*[가-힣].*"))korean=true;}assertTrue("Korean ETF names must be decoded correctly",korean);
    }
}
