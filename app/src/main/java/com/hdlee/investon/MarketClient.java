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
        c.setRequestProperty("Referer", "https://m.stock.naver.com/");
        try {
            if(c.getResponseCode()!=200) throw new IOException("시세 제공처 응답 " + c.getResponseCode());
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[8192]; int n;
                while((n=in.read(b))!=-1) { out.write(b,0,n); if(out.size()>8_000_000) throw new IOException("응답 크기 초과"); }
                String contentType=c.getContentType();
                java.nio.charset.Charset charset=StandardCharsets.UTF_8;
                if(contentType!=null&&contentType.toLowerCase(Locale.ROOT).contains("euc-kr")) charset=java.nio.charset.Charset.forName("EUC-KR");
                return new String(out.toByteArray(),charset);
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
        if(s.equals("^KS11")||s.equals("^KQ11")) {
            // KOSPI/KOSDAQ: NAVER index quote is the primary (intraday) source, Yahoo is the fallback.
            try { q=naverIndexQuote(s); } catch(Exception e) { q=yahooQuote(s); }
        } else if(s.matches("[A-Z0-9]{6}\\.(KS|KQ)")) {
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
    /** Today's change is always measured against the previous trading day's close. */
    JSONObject naverIndexQuote(String s) throws Exception {
        String code=s.equals("^KS11")?"KOSPI":"KOSDAQ";
        JSONObject o=new JSONObject(get("https://m.stock.naver.com/api/index/"+code+"/basic"));
        double price=number(o.getString("closePrice"));
        double change=number(o.getString("compareToPreviousClosePrice"));
        String direction=o.optJSONObject("compareToPreviousPrice")!=null?o.getJSONObject("compareToPreviousPrice").optString("code"):"";
        if("4".equals(direction)||"5".equals(direction)) change=-Math.abs(change);
        else if("1".equals(direction)||"2".equals(direction)) change=Math.abs(change);
        else if("3".equals(direction)) change=0;
        if(!Double.isFinite(price)||price<=0) throw new IOException("지수 가격 없음");
        double previous=price-change;
        if(!Double.isFinite(previous)||previous<=0) throw new IOException("전일 종가 없음");
        String trade=o.optString("localTradedAt","");
        long time=0;
        try { time=java.time.LocalDateTime.parse(trade.replace(" ","T")).atZone(java.time.ZoneId.of("Asia/Seoul")).toEpochSecond(); }
        catch(Exception ignored) { try { time=java.time.OffsetDateTime.parse(trade).toEpochSecond(); } catch(Exception ignored2) {} }
        return new JSONObject().put("symbol",s).put("price",price).put("previous",previous)
            .put("currency","KRW").put("time",time).put("tradeLabel",trade)
            .put("source","NAVER · 지수").put("market",o.optString("marketStatus",""));
    }
    JSONObject yahooQuote(String s) throws Exception {
        JSONObject chart=yahooChart(s,"5d");
        JSONObject m=chart.getJSONObject("meta");
        double p=m.getDouble("regularMarketPrice");
        if(!Double.isFinite(p)||p<=0) throw new IOException("유효한 가격 없음");
        // Indices: derive the previous close from daily bars so "today" is never measured against the 5-day range start.
        double previous=s.startsWith("^")||s.endsWith("=X")?Double.NaN:m.optDouble("previousClose",Double.NaN);
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
            if(!s.matches("[A-Z0-9]{6}\\.(KS|KQ)")) throw e;
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
    static String normalized(String value) { return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    JSONArray etfs() throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("catalog-v2",0);
        String cached=prefs.getString("etfs", "");
        if(!cached.isEmpty()&&System.currentTimeMillis()-prefs.getLong("time",0)<86_400_000) return new JSONArray(cached);
        try {
            JSONArray items=new JSONObject(get("https://finance.naver.com/api/sise/etfItemList.nhn")).getJSONObject("result").getJSONArray("etfItemList");
            JSONArray list=new JSONArray();
            for(int i=0;i<items.length();i++) { JSONObject item=items.getJSONObject(i); String code=item.getString("itemcode").toUpperCase(Locale.ROOT);
                if(code.matches("[A-Z0-9]{6}")) list.put(new JSONObject().put("symbol",code+".KS").put("name",item.getString("itemname")).put("currency","KRW").put("type","ETF"));
            }
            if(list.length()==0) throw new IOException("ETF 목록 없음");
            prefs.edit().putString("etfs",list.toString()).putLong("time",System.currentTimeMillis()).apply(); return list;
        } catch(Exception e) { if(!cached.isEmpty()) return new JSONArray(cached); throw e; }
    }
    public JSONArray search(String query) throws Exception {
        if(query.length()>80) throw new IllegalArgumentException("검색어가 너무 깁니다");
        JSONArray out=new JSONArray(); Set<String> seen=new HashSet<>(); Exception last=null;
        String key=normalized(query);
        if(key.isEmpty()) return out;
        try {
            JSONArray catalog=etfs(); List<JSONObject> matches=new ArrayList<>();
            for(int i=0;i<catalog.length();i++) { JSONObject item=catalog.getJSONObject(i);
                if(normalized(item.getString("name")).contains(key)||normalized(item.getString("symbol")).contains(key)) matches.add(item);
            }
            matches.sort(Comparator.comparingInt(item-> { String name=normalized(item.optString("name")); return name.equals(key)?0:name.startsWith(key)?1:2; }));
            for(JSONObject item:matches) if(seen.add(item.getString("symbol"))) out.put(item);
            // Substring searches such as 200 must use the full ETF catalog, not autocomplete.
            if(out.length()>0&&(key.matches("[0-9]{1,6}")||key.matches(".*(kodex|tiger|rise|ace|sol|plus|kosef|hanaro|etf).*"))) return out;
        } catch(Exception e) { last=e; }
        try {
            JSONObject n=new JSONObject(get("https://m.stock.naver.com/front-api/search/autoComplete?query="+encode(query)+"&target=stock,index"));
            collectDomestic(n,out,seen);
        } catch(Exception e) { last=e; }
        if(out.length()==0) try {
            JSONObject n=new JSONObject(get("https://ac.finance.naver.com/ac?q="+encode(query)+"&q_enc=UTF-8&st=111&sug=all&frm=stock"));
            collectNaver(n.getJSONArray("items"),out,seen);
        } catch(Exception e) { last=e; }
        if(out.length()>0&&query.matches(".*[가-힣].*")) return out;
        try {
            String path="/v1/finance/search?q="+encode(query)+"&quotesCount=20&newsCount=0";
            JSONObject y;
            try { y=new JSONObject(get("https://query1.finance.yahoo.com"+path)); }
            catch(Exception first) { y=new JSONObject(get("https://query2.finance.yahoo.com"+path)); }
            JSONArray a=y.optJSONArray("quotes");
            if(a!=null) for(int i=0;i<a.length();i++) {
                JSONObject v=a.getJSONObject(i); String kind=v.optString("quoteType"),s=v.optString("symbol");
                boolean kr=s.endsWith(".KS")||s.endsWith(".KQ")||s.equals("^KS11")||s.equals("^KQ11");
                boolean us=Arrays.asList("NMS","NYQ","NGM","NCM","ASE","PCX","BTS","NASDAQ","NYSE","NYSEArca").contains(v.optString("exchange"))||(kind.equals("INDEX")&&s.startsWith("^")&&s.matches("\\^[A-Z0-9]{2,8}"));
                if((kr||us)&&Arrays.asList("EQUITY","ETF","INDEX").contains(kind)&&seen.add(s))
                    out.put(new JSONObject().put("symbol",s).put("name",v.optString("shortname",v.optString("longname",s))).put("currency",kr?"KRW":"USD").put("type",kind));
            }
        } catch(Exception e) { last=e; }
        if(out.length()==0&&query.matches("[A-Z0-9]{6}")) {
            JSONObject o=new JSONObject(get("https://m.stock.naver.com/api/stock/"+query+"/basic"));
            String ex=o.optJSONObject("stockExchangeType")!=null?o.getJSONObject("stockExchangeType").optString("code"):"";
            out.put(new JSONObject().put("symbol",query+(ex.contains("KOSDAQ")?".KQ":".KS")).put("name",o.getString("stockName")).put("currency","KRW").put("type","EQUITY"));
        }
        if(out.length()==0&&last!=null) throw new IOException("검색 연결 실패 · 종목 코드로 다시 검색해 주세요",last);
        return out;
    }
    void collectDomestic(Object value,JSONArray out,Set<String> seen) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject item=(JSONObject)value;
            String code=item.optString("code"),name=item.optString("name"),url=item.optString("url"),reuters=item.optString("reutersCode");
            if(code.matches("[A-Z0-9]{6}")&&!name.isEmpty()&&(url.contains("/domestic/stock/")||reuters.matches("[A-Z0-9]{6}\\.(KS|KQ)"))) {
                String s=reuters.matches("[A-Z0-9]{6}\\.(KS|KQ)")?reuters:code+(item.optString("typeName").contains("코스닥")?".KQ":".KS");
                if(seen.add(s))out.put(new JSONObject().put("symbol",s).put("name",name).put("currency","KRW").put("type","EQUITY"));
                return;
            }
            Iterator<String> keys=item.keys();while(keys.hasNext())collectDomestic(item.opt(keys.next()),out,seen);
        } else if(value instanceof JSONArray) { JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)collectDomestic(a.opt(i),out,seen); }
    }
    static String field(JSONArray a,int index) { JSONArray child=a.optJSONArray(index); return child!=null?child.optString(0):a.optString(index); }
    void collectNaver(JSONArray a,JSONArray out,Set<String> seen) throws Exception {
        if(a.length()>=2) {
            String first=field(a,0),second=field(a,1),code="",name="";
            // Both name/code and code/name, including single-element nested fields.
            if(first.matches("[A-Z0-9]{6}")) {code=first;name=second;}
            else if(second.matches("[A-Z0-9]{6}")) {code=second;name=first;}
            if(!code.isEmpty()&&!name.isEmpty()) {
                String market=field(a,2),symbol=code+(market.contains("코스닥")||market.contains("KOSDAQ")?".KQ":".KS");
                if(seen.add(symbol))out.put(new JSONObject().put("symbol",symbol).put("name",name).put("currency","KRW").put("type","EQUITY"));
                return;
            }
        }
        for(int i=0;i<a.length();i++)if(a.optJSONArray(i)!=null)collectNaver(a.getJSONArray(i),out,seen);
    }
    /** Recent posts of the 메르 blog (blogId ranto28): RSS first, mobile list endpoint as fallback. */
    public JSONArray blog() throws Exception {
        JSONArray out=new JSONArray();
        try { blogFromRss(out); } catch(Exception ignored) { }
        if(out.length()==0) blogFromList(out);
        if(out.length()==0) throw new IOException("블로그 글을 불러오지 못했습니다");
        return out;
    }
    static String blogLink(String logNo) { return "https://m.blog.naver.com/ranto28/"+logNo; }
    void blogFromRss(JSONArray out) throws Exception {
        String xml=get("https://rss.blog.naver.com/ranto28.xml");
        XmlPullParser p=Xml.newPullParser(); p.setInput(new StringReader(xml));
        JSONObject item=null; String tag="";
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT&&out.length()<5;e=p.next()) {
            if(e==XmlPullParser.START_TAG) { tag=p.getName(); if(tag.equals("item")) item=new JSONObject(); }
            if((e==XmlPullParser.TEXT||e==XmlPullParser.CDSECT)&&item!=null&&Arrays.asList("title","link","pubDate").contains(tag)) item.put(tag,item.optString(tag)+p.getText());
            if(e==XmlPullParser.END_TAG) {
                if(p.getName().equals("item")&&item!=null) {
                    java.util.regex.Matcher m=java.util.regex.Pattern.compile("ranto28/(\\d+)").matcher(item.optString("link"));
                    String title=item.optString("title").trim();
                    if(m.find()&&!title.isEmpty()) out.put(new JSONObject().put("title",title).put("link",blogLink(m.group(1))).put("date",rssDate(item.optString("pubDate"))));
                    item=null;
                }
                tag="";
            }
        }
    }
    static String rssDate(String v) {
        try {
            java.text.SimpleDateFormat in=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",Locale.ENGLISH);
            java.text.SimpleDateFormat o=new java.text.SimpleDateFormat("yyyy.M.d.",Locale.KOREA);
            o.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
            return o.format(in.parse(v.trim()));
        } catch(Exception e) { return ""; }
    }
    void blogFromList(JSONArray out) throws Exception {
        String body=get("https://blog.naver.com/PostTitleListAsync.naver?blogId=ranto28&viewdate=&currentPage=1&categoryNo=&parentCategoryNo=&countPerPage=5");
        String[] parts=body.split("\"logNo\"");
        java.util.regex.Pattern title=java.util.regex.Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\""),date=java.util.regex.Pattern.compile("\"addDate\"\\s*:\\s*\"([^\"]*)\"");
        for(int i=1;i<parts.length&&out.length()<5;i++) {
            java.util.regex.Matcher id=java.util.regex.Pattern.compile("^\\s*:\\s*\"?(\\d+)").matcher(parts[i]);
            java.util.regex.Matcher t=title.matcher(parts[i]);
            if(!id.find()||!t.find()) continue;
            String name=URLDecoder.decode(t.group(1).replace("+"," "),"UTF-8").trim();
            java.util.regex.Matcher d=date.matcher(parts[i]);
            String when=d.find()?URLDecoder.decode(d.group(1).replace("+"," "),"UTF-8").trim():"";
            if(!name.isEmpty()) out.put(new JSONObject().put("title",name).put("link",blogLink(id.group(1))).put("date",when));
        }
    }
    public JSONArray news() throws Exception {
        String xml=get("https://news.google.com/rss/search?q="+encode("한국 증시 주식 ETF when:1d")+"&hl=ko&gl=KR&ceid=KR:ko");
        XmlPullParser p=Xml.newPullParser(); p.setInput(new StringReader(xml));
        JSONArray out=new JSONArray(); JSONObject item=null; String tag="";
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT&&out.length()<10;e=p.next()) {
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
