package com.javaee.documentservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.dto.ToolboxJobRequest;
import com.javaee.documentservice.util.DocumentParserUtil;
import com.javaee.documentservice.vo.DocumentVO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolboxJobService {
    private static final String EXCHANGE = "file.exchange", QUEUE = "document.toolbox.queue", KEY = "document.toolbox";
    private final DocumentService documents; private final RabbitTemplate rabbit; private final RestTemplate http = new RestTemplate();
    private final Map<String, Map<String,Object>> jobs = new ConcurrentHashMap<>(); private final Map<String, byte[]> files = new ConcurrentHashMap<>(); private final Map<String, String> jobTokens = new ConcurrentHashMap<>(); private final ThreadLocal<String> requestToken = new ThreadLocal<>();
    @Value("${file.service.url:http://localhost:8082}") private String fileUrl;
    public ToolboxJobService(DocumentService documents, RabbitTemplate rabbit) { this.documents=documents; this.rabbit=rabbit; }
    public Map<String,Object> submit(ToolboxJobRequest r, Long userId, String authorization) {
        if(r.getDocumentIds()==null||r.getDocumentIds().isEmpty()) throw new BusinessException("请选择待处理文档");
        String type=Optional.ofNullable(r.getToolType()).orElse("").toUpperCase(Locale.ROOT);
        if(!Set.of("OCR","PDF_SPLIT","PDF_MERGE","WORD_TABLE_EXCEL").contains(type)) throw new BusinessException("不支持的工具类型");
        if("PDF_MERGE".equals(type)&&r.getDocumentIds().size()<2) throw new BusinessException("合并 PDF 至少选择两份文档");
        r.getDocumentIds().forEach(id->documents.getById(id,userId)); String id=UUID.randomUUID().toString().replace("-","");
        Map<String,Object> j=new ConcurrentHashMap<>(); j.put("jobId",id);j.put("userId",userId);j.put("toolType",type);j.put("documentIds",r.getDocumentIds());j.put("pages", Optional.ofNullable(r.getPages()).orElse(""));j.put("status","PENDING");j.put("progress",5);j.put("message","任务已进入队列");j.put("createdAt",Instant.now().toString());jobs.put(id,j); jobTokens.put(id, authorization); rabbit.convertAndSend(EXCHANGE,KEY,id);return snapshot(id,userId);
    }
    public Map<String,Object> snapshot(String id,Long userId){Map<String,Object>j=owned(id,userId);return new LinkedHashMap<>(j);} public byte[] output(String id,Long userId){owned(id,userId);byte[]b=files.get(id);if(b==null)throw new BusinessException("结果文件尚未生成");return b;}
    @RabbitListener(queues=QUEUE) public void process(String id){Map<String,Object>j=jobs.get(id);if(j==null)return;requestToken.set(jobTokens.get(id));try{update(j,"PROCESSING",15,"正在读取原始文件");@SuppressWarnings("unchecked")List<String>ids=(List<String>)j.get("documentIds");Long user=((Number)j.get("userId")).longValue();List<DocumentVO>ds=new ArrayList<>();for(String docId:ids)ds.add(documents.getById(docId,user));String t=(String)j.get("toolType");byte[]out=switch(t){case"OCR"->ocr(ds.get(0),j);case"PDF_SPLIT"->split(ds.get(0),(String)j.get("pages"),j);case"PDF_MERGE"->merge(ds,j);default->excel(ds.get(0),j);};files.put(id,out);j.put("fileName",name(t));j.put("contentType",mime(t));update(j,"SUCCESS",100,"处理完成，可下载结果文件");}catch(Exception e){update(j,"FAILED",100,"处理失败："+e.getMessage());}finally{requestToken.remove(); jobTokens.remove(id);}}
    private byte[] ocr(DocumentVO d,Map<String,Object>j){update(j,"PROCESSING",50,"正在进行 OCR 识别");String t=DocumentParserUtil.parseDocument(download(d),d.getTitle());if(t.isBlank())throw new BusinessException("未识别到文字，请确认已安装中文 OCR 语言包");return t.getBytes(StandardCharsets.UTF_8);}
    private byte[] split(DocumentVO d,String raw,Map<String,Object>j)throws Exception{update(j,"PROCESSING",50,"正在拆分 PDF 页面");Set<Integer>pages=pages(raw);try(PDDocument in=Loader.loadPDF(download(d));PDDocument out=new PDDocument();ByteArrayOutputStream b=new ByteArrayOutputStream()){for(int i=0;i<in.getNumberOfPages();i++)if(pages.isEmpty()||pages.contains(i+1))out.importPage(in.getPage(i));if(out.getNumberOfPages()==0)throw new BusinessException("指定页码不存在");out.save(b);return b.toByteArray();}}
    private byte[] merge(List<DocumentVO>ds,Map<String,Object>j)throws Exception{try(PDDocument out=new PDDocument();ByteArrayOutputStream b=new ByteArrayOutputStream()){for(int i=0;i<ds.size();i++){update(j,"PROCESSING",25+i*55/ds.size(),"正在合并第 "+(i+1)+" 份 PDF");try(PDDocument in=Loader.loadPDF(download(ds.get(i)))){for(int p=0;p<in.getNumberOfPages();p++)out.importPage(in.getPage(p));}}out.save(b);return b.toByteArray();}}
    private byte[] excel(DocumentVO d,Map<String,Object>j)throws Exception{update(j,"PROCESSING",50,"正在提取 Word 表格");try(XWPFDocument word=new XWPFDocument(new ByteArrayInputStream(download(d)));XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream b=new ByteArrayOutputStream()){if(word.getTables().isEmpty())throw new BusinessException("Word 中没有可导出的表格");int n=1;for(var table:word.getTables()){var sheet=book.createSheet("表格"+n++);int r=0;for(var row:table.getRows()){var er=sheet.createRow(r++);for(int c=0;c<row.getTableCells().size();c++)er.createCell(c).setCellValue(row.getCell(c).getText());}}book.write(b);return b.toByteArray();}}
    private byte[] download(DocumentVO d){if(d.getFileId()==null||d.getFileId().isBlank())throw new BusinessException("该文档没有关联原始文件");var headers = new org.springframework.http.HttpHeaders(); headers.set("Authorization", requestToken.get()); var response = http.exchange(fileUrl+"/api/files/download/"+d.getFileId(), org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), byte[].class); return response.getBody();}
    private Map<String,Object> owned(String id,Long u){Map<String,Object>j=jobs.get(id);if(j==null)throw new BusinessException("任务不存在或已过期");if(!u.equals(((Number)j.get("userId")).longValue()))throw new BusinessException("无权访问该任务");return j;} private void update(Map<String,Object>j,String s,int p,String m){j.put("status",s);j.put("progress",p);j.put("message",m);j.put("updatedAt",Instant.now().toString());}
    private Set<Integer> pages(String r){Set<Integer>s=new TreeSet<>();if(r==null||r.isBlank())return s;for(String x:r.split(",")){String[]a=x.trim().split("-");int from=Integer.parseInt(a[0].trim()),to=a.length>1?Integer.parseInt(a[1].trim()):from;for(int i=Math.min(from,to);i<=Math.max(from,to);i++)s.add(i);}return s;} private String name(String t){return switch(t){case"OCR"->"ocr-result.txt";case"WORD_TABLE_EXCEL"->"word-tables.xlsx";case"PDF_SPLIT"->"pdf-pages.pdf";default->"merged.pdf";};} private String mime(String t){return switch(t){case"OCR"->"text/plain;charset=UTF-8";case"WORD_TABLE_EXCEL"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";default->"application/pdf";};}
}
