package com.hdlee.investon;

import android.content.Context;
import android.util.Xml;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Network I/O never runs on the UI thread. Public feeds are best-effort, not an exchange SLA. */
public final class MarketClient {
    private final Context context;
    public MarketClient(Context c) { context = c.getApplicationContext(); }
    static String encode(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch(Exception e) { throw new IllegalArgumentException(e); } }
    static String symbol(String s) {
        if (s == null || !s.matches("[A-Za-z0-9^.=\\-]{1,32}")) throw new IllegalArgumentException("잘못된 종목 코드");
        return s.toUpperCase(Locale.ROOT);
    }
    String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 InvestOn/1.0");
        c.setRequestProperty("Accept", "application/json,text/xml,*/*");
        try {
            if(c.getResponseCode()!=200) throw new IOException("시세 제공처 응답 " + c.getResponseCode());
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[8192]; int n;
                while((n=in.read(b))!=-1) { out.write(b,0,n); if(out.size()>8_000_000) throw new IOException("응답 크기 초과"); }
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
        } finally { c.disconnect(); }
    }
    JSONObject yahooChart(String s, String range) throws Exception {
        Exception last=null;
        for(String host: new String[]{"query1.finance.yahoo.com","query2.finance.yahoo.com"}) {
            try { JSONObject root=new JSONObject(get("https://"+host+"/v8/finance/chart/"+encode(symbol(s))+"?range="+range+"&interval=1d"));
                JSONArray a=root.getJSONObject("chart").optJSONArray("result");
                if(a==null||a.length()==0) throw new IOException("해당 종목 시세가 없습니다");
                return a.getJSONObject(0);
            } catch(Exception e) { last=e; }
        }
        throw last;
    }
    static double number(String s) { return Double.parseDouble(s.replace(",", "")); }
    public JSONObject quote(String raw) throws Exception {
        String s=symbol(raw); JSONObject q;
        if(s.matches("[0-9]{6}\\.(KS|KQ)")) {
            try {
                JSONObject o=new JSONObject(get("https://m.stock.naver.com/api/stock/"+s.substring(0,6)+"/basic"));
                double price=number(o.getString("closePrice"));
                double change=number(o.getString("compareToPreviousClosePrice"));
                String direction=o.optJSONObject("compareToPreviousPrice")!=null?o.getJSONObject("compareToPreviousPrice").optString("code"):"";
                if("4".equals(direction)||"5".equals(direction)) change=-Math.abs(change);
                if(!Double.isFinite(price)||price<=0) throw new IOException("가격 없음");
                // Timestamp is the provider's local trade time, never a fabricated realtime stamp.
                String trade=o.optString("localTradedAt", "");
                long time=0;
                try { time=java.time.LocalDateTime.parse(trade.replace(" ","T")).atZone(java.time.ZoneId.of("Asia/Seoul")).toEpochSecond(); }
                catch(Exception ignored) { try { time=java.time.OffsetDateTime.parse(trade).toEpochSecond(); } catch(Exception ignored2) {} }
                q=new JSONObject().put("symbol",s).put("price",price).put("previous",price-change)
                    .put("currency","KRW").put("time",time).put("tradeLabel",trade)
                    .put("source","NAVER · 공개 시세").put("market",o.optString("marketStatus",""));
            } catch(Exception e) { q=yahooQuote(s); }
        } else q=yahooQuote(s);
        q.put("fetchedAt",System.currentTimeMillis());
        context.getSharedPreferences("quotes",0).edit().putString(s,q.toString()).apply();
        return q;
    }
    JSONObject yahooQuote(String s) throws Exception {
        JSONObject chart=yahooChart(s,"5d");
        JSONObject m=chart.getJSONObject("meta");
        double p=m.getDouble("regularMarketPrice");
        if(!Double.isFinite(p)||p<=0) throw new IOException("유효한 가격 없음");
        double previous=m.optDouble("previousClose",Double.NaN);
        if(!Double.isFinite(previous)) {
            java.time.ZoneId zone=java.time.ZoneId.of(m.optString("exchangeTimezoneName","America/New_York"));
            java.time.LocalDate latest=java.time.Instant.ofEpochSecond(m.optLong("regularMarketTime",0)).atZone(zone).toLocalDate();
            JSONArray timestamps=chart.optJSONArray("timestamp");
            JSONArray closes=chart.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");
            if(timestamps!=null) for(int i=0;i<timestamps.length();i++) {
                java.time.LocalDate day=java.time.Instant.ofEpochSecond(timestamps.getLong(i)).atZone(zone).toLocalDate();
                double close=closes.optDouble(i,Double.NaN);
                if(day.isBefore(latest)&&Double.isFinite(close)&&close>0) previous=close;
            }
        }
        return new JSONObject().put("symbol",s).put("price",p).put("previous",Double.isFinite(previous)?previous:JSONObject.NULL)
            .put("currency",m.optString("currency","USD")).put("time",m.optLong("regularMarketTime",0))
            .put("source","Yahoo Finance · 지연 가능").put("market",m.optString("exchangeName",""));
    }
    public JSONObject history(String raw) throws Exception {
        String s=symbol(raw);
        File f=new File(context.getCacheDir(),"history-"+s.replaceAll("[^A-Za-z0-9]","_")+".json");
        if(f.exists()&&System.currentTimeMillis()-f.lastModified()<3_600_000) {
            try { return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)); } catch(Exception ignored) {}
        }
        JSONObject result;
        try {
            JSONObject o=yahooChart(s,"1y"); JSONArray ts=o.getJSONArray("timestamp");
            JSONObject values=o.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
            JSONArray closes=values.getJSONArray("close"), highs=values.getJSONArray("high"), lows=values.getJSONArray("low");
            JSONArray points=new JSONArray(); double high=-Double.MAX_VALUE,low=Double.MAX_VALUE;
            for(int i=0;i<ts.length();i++) {
                double close=closes.optDouble(i,Double.NaN); if(!Double.isFinite(close)) continue;
                double h=highs.optDouble(i,close),l=lows.optDouble(i,close);
                if(Double.isFinite(h)) high=Math.max(high,h); if(Double.isFinite(l)) low=Math.min(low,l);
                points.put(new JSONObject().put("t",ts.getLong(i)).put("p",close));
            }
            if(points.length()<2) throw new IOException("1년 차트 자료가 부족합니다");
            result=new JSONObject().put("points",points).put("high",high).put("low",low).put("source","Yahoo Finance");
        } catch(Exception e) {
            if(!s.matches("[0-9]{6}\\.(KS|KQ)")) throw e;
            result=naverHistory(s.substring(0,6));
        }
        try { java.nio.file.Files.write(f.toPath(),result.toString().getBytes(StandardCharsets.UTF_8)); } catch(Exception ignored) {}
        return result;
    }
    JSONObject naverHistory(String code) throws Exception {
        String xml=get("https://fchart.stock.naver.com/sise.nhn?symbol="+code+"&timeframe=day&count=260&requestType=0");
        XmlPullParser p=Xml.newPullParser(); p.setInput(new StringReader(xml));
        JSONArray points=new JSONArray(); double high=-Double.MAX_VALUE,low=Double.MAX_VALUE;
        long cutoff=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toEpochSecond();
        for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.next()) {
            if(event==XmlPullParser.START_TAG&&p.getName().equals("item")) {
                String[] v=p.getAttributeValue(null,"data").split("\\|"); if(v.length<5) continue;
                long t=java.time.LocalDate.parse(v[0],java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toEpochSecond();
                if(t<cutoff) continue;
                double close=number(v[4]); if(close<=0) continue;
                high=Math.max(high,number(v[2])); low=Math.min(low,number(v[3]));
                points.put(new JSONObject().put("t",t).put("p",close));
            }
        }
        if(points.length()<2) throw new IOException("차트 자료 없음");
        return new JSONObject().put("points",points).put("high",high).put("low",low).put("source","NAVER");
    }
    public JSONArray search(String query) throws Exception {
        if(query.length()>80) throw new IllegalArgumentException("검색어가 너무 깁니다");
        JSONArray out=new JSONArray(); Set<String> seen=new HashSet<>(); Exception last=null;
        try {
            JSONObject n=new JSONObject(get("https://ac.finance.naver.com/ac?q="+encode(query)+"&q_enc=UTF-8&st=111&r_format=json&r_enc=UTF-8"));
            collectNaver(n.getJSONArray("items"),out,seen);
        } catch(Exception e) { last=e; }
        try {
            JSONObject y=new JSONObject(get("https://query1.finance.yahoo.com/v1/finance/search?q="+encode(query)+"&quotesCount=15&newsCount=0"));
            JSONArray a=y.optJSONArray("quotes");
            if(a!=null) for(int i=0;i<a.length();i++) {
                JSONObject v=a.getJSONObject(i); String kind=v.optString("quoteType"),s=v.optString("symbol");
                boolean kr=s.endsWith(".KS")||s.endsWith(".KQ")||s.equals("^KS11")||s.equals("^KQ11");
                boolean us=Arrays.asList("NMS","NYQ","NGM","NCM","ASE","PCX","BTS","NASDAQ","NYSE","NYSEArca").contains(v.optString("exchange"));
                if((kr||us)&&Arrays.asList("EQUITY","ETF","INDEX").contains(kind)&&seen.add(s))
                    out.put(new JSONObject().put("symbol",s).put("name",v.optString("shortname",v.optString("longname",s))).put("currency",kr?"KRW":"USD").put("type",kind));
            }
        } catch(Exception e) { last=e; }
        if(out.length()==0&&query.matches("[0-9]{6}")) {
            JSONObject o=new JSONObject(get("https://m.stock.naver.com/api/stock/"+query+"/basic"));
            String ex=o.optJSONObject("stockExchangeType")!=null?o.getJSONObject("stockExchangeType").optString("code"):"";
            out.put(new JSONObject().put("symbol",query+(ex.contains("KOSDAQ")?".KQ":".KS")).put("name",o.getString("stockName")).put("currency","KRW").put("type","EQUITY"));
        }
        if(out.length()==0&&last!=null) throw new IOException("검색 연결 실패 · 종목 코드로 다시 검색해 주세요",last);
        return out;
    }
    void collectNaver(JSONArray a,JSONArray out,Set<String> seen) throws Exception {
        // Naver autocomplete nests [code],[name],[market] within result groups.
        if(a.length()>=2&&a.optJSONArray(0)!=null&&a.optJSONArray(1)!=null) {
            String code=a.getJSONArray(0).optString(0),name=a.getJSONArray(1).optString(0);
            if(code.matches("[0-9]{6}")) {
                String market=a.optJSONArray(2)!=null?a.getJSONArray(2).optString(0):"";
                String s=code+(market.contains("코스닥")||market.contains("KOSDAQ")?".KQ":".KS");
                if(seen.add(s)) out.put(new JSONObject().put("symbol",s).put("name",name).put("currency","KRW").put("type","EQUITY"));
                return;
            }
        }
        for(int i=0;i<a.length();i++) if(a.optJSONArray(i)!=null) collectNaver(a.getJSONArray(i),out,seen);
    }
    public JSONArray news() throws Exception {
        String xml=get("https://news.google.com/rss/search?q="+encode("한국 증시 주식 ETF when:1d")+"&hl=ko&gl=KR&ceid=KR:ko");
        XmlPullParser p=Xml.newPullParser(); p.setInput(new StringReader(xml));
        JSONArray out=new JSONArray(); JSONObject item=null; String tag="";
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT&&out.length()<5;e=p.next()) {
            if(e==XmlPullParser.START_TAG) { tag=p.getName(); if(tag.equals("item")) item=new JSONObject(); }
            if(e==XmlPullParser.TEXT&&item!=null&&Arrays.asList("title","link","pubDate","source").contains(tag)) item.put(tag,item.optString(tag)+p.getText());
            if(e==XmlPullParser.END_TAG) {
                if(p.getName().equals("item")&&item!=null) { if(item.has("title")&&item.has("link")) out.put(item); item=null; }
                tag="";
            }
        }
        return out;
    }
}
