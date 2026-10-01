package com.hdlee.investon;

import android.util.Xml;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Editable Office Open XML backup. JSON is only the internal WebView transport. */
public final class BackupWorkbook {
    public static final String MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String[] NAMES={"관심종목","계좌","보유종목","가격알림"};
    static final String[][] HEADERS={{"순서","종목코드","종목명","통화","유형"},{"계좌명"},{"계좌명","종목코드","종목명","통화","유형","수량","매입단가","ID"},{"종목코드","종목명","통화","유형","목표가","조건","사용","ID"}};
    static String xml(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
    static String col(int n) { String s=""; for(n++;n>0;n=(n-1)/26)s=(char)('A'+(n-1)%26)+s;return s; }
    static void entry(ZipOutputStream zip,String path,String text) throws IOException { zip.putNextEntry(new ZipEntry(path));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry(); }
    static List<List<Object>> rows(JSONObject state,int sheet) throws JSONException {
        List<List<Object>> rows=new ArrayList<>();rows.add(new ArrayList<>(Arrays.asList(HEADERS[sheet])));
        JSONArray a=state.getJSONArray(new String[]{"watch","accounts","holdings","rules"}[sheet]);
        for(int i=0;i<a.length();i++) {
            if(sheet==1) { rows.add(Arrays.asList(a.getString(i)));continue; }
            JSONObject o=a.getJSONObject(i);String symbol=o.getString("symbol"),name=o.getString("name"),currency=o.getString("currency"),type=o.optString("type","EQUITY");
            if(sheet==0) rows.add(Arrays.asList(i+1,symbol,name,currency,type));
            if(sheet==2) rows.add(Arrays.asList(o.getString("account"),symbol,name,currency,type,o.getDouble("quantity"),o.getDouble("cost"),o.getString("id")));
            if(sheet==3) rows.add(Arrays.asList(symbol,name,currency,type,o.getDouble("target"),o.getString("direction"),o.getBoolean("active"),o.getString("id")));
        }
        return rows;
    }
    public static void write(JSONObject state,OutputStream output) throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            StringBuilder types=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder wb=new StringBuilder("<workbook xmlns=\""+NS+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for(int i=0;i<NAMES.length;i++) {
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(i+1).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                wb.append("<sheet name=\"").append(NAMES[i]).append("\" sheetId=\"").append(i+1).append("\" r:id=\"rId").append(i+1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i+1).append(".xml\"/>");
                List<List<Object>> rows=rows(state,i);
                StringBuilder sheet=new StringBuilder("<worksheet xmlns=\""+NS+"\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"22\"/><cols>");
                for(int c=0;c<HEADERS[i].length;c++)sheet.append("<col min=\"").append(c+1).append("\" max=\"").append(c+1).append("\" width=\"").append(HEADERS[i][c].equals("종목명")?32:HEADERS[i][c].equals("ID")?28:19).append("\" customWidth=\"1\"/>");
                sheet.append("</cols><sheetData>");
                for(int r=0;r<rows.size();r++) {
                    sheet.append("<row r=\"").append(r+1).append("\">");
                    for(int c=0;c<rows.get(r).size();c++) { Object v=rows.get(r).get(c);String ref=col(c)+(r+1);sheet.append("<c r=\"").append(ref).append("\" s=\"").append(r==0?1:v instanceof Number?2:0).append("\"");
                        if(v instanceof Number)sheet.append("><v>").append(v).append("</v></c>");
                        else if(v instanceof Boolean)sheet.append(" t=\"b\"><v>").append((Boolean)v?1:0).append("</v></c>");
                        else sheet.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(String.valueOf(v))).append("</t></is></c>");
                    }sheet.append("</row>");
                }
                sheet.append("</sheetData><autoFilter ref=\"A1:").append(col(HEADERS[i].length-1)).append(rows.size()).append("\"/></worksheet>");
                entry(zip,"xl/worksheets/sheet"+(i+1)+".xml",sheet.toString());
            }
            entry(zip,"[Content_Types].xml",types.append("</Types>").toString());
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml",wb.append("</sheets></workbook>").toString());
            entry(zip,"xl/_rels/workbook.xml.rels",rels.append("<Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>").toString());
            entry(zip,"xl/styles.xml","<styleSheet xmlns=\""+NS+"\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.######\"/></numFmts><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"맑은 고딕\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF177B6D\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"3\"><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" numFmtId=\"0\" xfId=\"0\"/><xf fontId=\"1\" fillId=\"2\" borderId=\"0\" numFmtId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" numFmtId=\"164\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        }
    }
    static XmlPullParser parser(byte[] bytes) throws Exception {
        if(bytes==null)throw new IOException("필수 엑셀 시트가 없습니다");
        String text=new String(bytes,StandardCharsets.UTF_8);
        if(text.contains("<!DOCTYPE")||text.contains("<!ENTITY"))throw new IOException("지원하지 않는 XML 형식");
        XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);p.setInput(new StringReader(text));return p;
    }
    static int column(String ref) throws IOException { int c=0;for(int i=0;i<ref.length()&&Character.isLetter(ref.charAt(i));i++)c=c*26+ref.charAt(i)-'A'+1;if(c<1||c>32)throw new IOException("잘못된 열 위치");return c-1; }
    static List<String> shared(byte[] bytes) throws Exception {
        List<String> list=new ArrayList<>();if(bytes==null)return list;XmlPullParser p=parser(bytes);StringBuilder text=null;
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next()) { if(e==XmlPullParser.START_TAG&&p.getName().equals("si"))text=new StringBuilder();else if(e==XmlPullParser.START_TAG&&p.getName().equals("t")&&text!=null)text.append(p.nextText());else if(e==XmlPullParser.END_TAG&&p.getName().equals("si")){list.add(text.toString());text=null;} }
        return list;
    }
    static List<Map<Integer,String>> table(byte[] bytes,List<String> strings) throws Exception {
        List<Map<Integer,String>> rows=new ArrayList<>();XmlPullParser p=parser(bytes);Map<Integer,String> row=null;int col=0;String type="",value="";boolean formula=false;
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next()) {
            if(e==XmlPullParser.START_TAG) { String name=p.getName();
                if(name.equals("row")){row=new HashMap<>();if(rows.size()>=201)throw new IOException("엑셀 행이 너무 많습니다");}
                if(name.equals("c")){col=column(p.getAttributeValue(null,"r"));type=p.getAttributeValue(null,"t");value="";formula=false;}
                if(name.equals("f"))formula=true;
                if(name.equals("v"))value=p.nextText();
                if(name.equals("t"))value+=p.nextText();
            } else if(e==XmlPullParser.END_TAG&&p.getName().equals("c")&&row!=null) {
                if(formula)throw new IOException("백업에는 수식 대신 값을 입력해 주세요");
                if("s".equals(type))value=strings.get(Integer.parseInt(value));
                if("b".equals(type))value=value.equals("1")?"true":"false";
                row.put(col,value);
            } else if(e==XmlPullParser.END_TAG&&p.getName().equals("row")&&row!=null) {if(row.values().stream().anyMatch(v->!v.isEmpty()))rows.add(row);row=null;}
        }return rows;
    }
    static String cell(Map<Integer,String> row,int c) {return row.getOrDefault(c,"").trim();}
    static double amount(String value) throws IOException {try{double n=Double.parseDouble(value.replace(",",""));if(!Double.isFinite(n)||n<=0)throw new Exception();return n;}catch(Exception e){throw new IOException("수량·가격·순서는 양수로 입력해 주세요");}}
    static JSONObject instrument(Map<Integer,String> row,int start) throws Exception {return new JSONObject().put("symbol",cell(row,start)).put("name",cell(row,start+1)).put("currency",cell(row,start+2)).put("type",cell(row,start+3));}
    public static JSONObject read(InputStream input) throws Exception {
        Map<String,byte[]> files=new HashMap<>();int total=0,count=0;
        try(ZipInputStream zip=new ZipInputStream(input)) { ZipEntry e;byte[] buffer=new byte[4096];while((e=zip.getNextEntry())!=null) {if(++count>100)throw new IOException("엑셀 파일이 너무 큽니다");String path=e.getName();if(path.contains("..")||path.startsWith("/"))throw new IOException("잘못된 파일 경로");ByteArrayOutputStream bytes=new ByteArrayOutputStream();int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>5_000_000)throw new IOException("엑셀 파일이 너무 큽니다");bytes.write(buffer,0,n);}if(files.put(path,bytes.toByteArray())!=null)throw new IOException("중복된 엑셀 파일 항목");} }
        Map<String,String> relations=new HashMap<>();XmlPullParser p=parser(files.get("xl/_rels/workbook.xml.rels"));
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next())if(e==XmlPullParser.START_TAG&&p.getName().equals("Relationship")){String target=p.getAttributeValue(null,"Target");if(target==null||target.contains("..")||p.getAttributeValue(null,"TargetMode")!=null)continue;relations.put(p.getAttributeValue(null,"Id"),target.startsWith("/")?target.substring(1):"xl/"+target);}
        Map<String,String> sheets=new HashMap<>();p=parser(files.get("xl/workbook.xml"));
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next())if(e==XmlPullParser.START_TAG&&p.getName().equals("sheet"))sheets.put(p.getAttributeValue(null,"name"),relations.get(p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id")));
        List<String> strings=shared(files.get("xl/sharedStrings.xml"));JSONObject state=new JSONObject().put("version",1);JSONArray watch=new JSONArray(),accounts=new JSONArray(),holdings=new JSONArray(),rules=new JSONArray();List<Map.Entry<Double,JSONObject>> ordered=new ArrayList<>();Set<Double> orders=new HashSet<>();
        for(int i=0;i<NAMES.length;i++) {List<Map<Integer,String>> rows=table(files.get(sheets.get(NAMES[i])),strings);if(rows.isEmpty())throw new IOException("엑셀 제목 행이 없습니다");for(int c=0;c<HEADERS[i].length;c++)if(!HEADERS[i][c].equals(cell(rows.get(0),c)))throw new IOException(NAMES[i]+" 시트의 열 제목을 유지해 주세요");
            for(int r=1;r<rows.size();r++){Map<Integer,String> row=rows.get(r);
                if(i==0){double order=amount(cell(row,0));if(order!=Math.rint(order)||!orders.add(order))throw new IOException("관심종목 순서는 중복 없는 정수여야 합니다");ordered.add(new AbstractMap.SimpleEntry<>(order,instrument(row,1)));}
                if(i==1)accounts.put(cell(row,0));
                if(i==2)holdings.put(instrument(row,1).put("account",cell(row,0)).put("quantity",amount(cell(row,5))).put("cost",amount(cell(row,6))).put("id",cell(row,7)));
                if(i==3){String active=cell(row,6).toLowerCase(Locale.ROOT);if(!Arrays.asList("true","false","1","0").contains(active))throw new IOException("알림 사용은 TRUE 또는 FALSE로 입력해 주세요");rules.put(instrument(row,0).put("target",amount(cell(row,4))).put("direction",cell(row,5)).put("active",active.equals("true")||active.equals("1")).put("id",cell(row,7)));}
            }
        }
        ordered.sort(Map.Entry.comparingByKey());for(Map.Entry<Double,JSONObject> item:ordered)watch.put(item.getValue());
        return state.put("watch",watch).put("accounts",accounts).put("holdings",holdings).put("rules",rules);
    }
}
