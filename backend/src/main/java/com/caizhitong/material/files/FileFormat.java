package com.caizhitong.material.files;

import java.security.*;
import java.util.HexFormat;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.w3c.dom.Document;
import static com.caizhitong.identity.IdentityInputs.invalid;

public final class FileFormat {
    private FileFormat() {}
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** Called only after the original bytes passed the real scanner. Images are re-encoded without source metadata. */
    public static byte[] validateAndSanitize(byte[] bytes,String mime,String purpose){
        try {
            if(mime.equals("image/png")||mime.equals("image/jpeg"))return image(bytes,mime);
            if(mime.equals("application/pdf")){
                if(bytes.length<8||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw invalid();
                try(var pdf=Loader.loadPDF(bytes)){if(pdf.isEncrypted()||pdf.getNumberOfPages()<1||pdf.getNumberOfPages()>500)throw invalid();}
                return bytes;
            }
            if(mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")){docx(bytes);return bytes;}
            if(mime.equals("text/csv")&&purpose.equals("student_roster")){
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if(text.startsWith("\uFEFF"))text=text.substring(1);
                if(text.chars().anyMatch(c->(c<32&&c!='\r'&&c!='\n'&&c!='\t')||c==127))throw invalid();
                if(!text.lines().findFirst().orElse("").equals("studentNumber,college,grade,status"))throw invalid();
                return bytes;
            }
            throw invalid();
        }catch(com.caizhitong.common.BusinessException e){throw e;}
        catch(Exception e){throw invalid();}
    }
    private static byte[] image(byte[] bytes,String mime)throws IOException {
        boolean png=mime.equals("image/png");
        byte[] magic=png?new byte[]{(byte)137,80,78,71,13,10,26,10}:new byte[]{(byte)255,(byte)216,(byte)255};
        if(bytes.length<magic.length||!Arrays.equals(Arrays.copyOf(bytes,magic.length),magic))throw invalid();
        try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw invalid();var reader=readers.next();
            try {
                reader.setInput(in,true,true);
                String format=reader.getFormatName();if(png?!format.equalsIgnoreCase("png"):!format.equalsIgnoreCase("jpeg"))throw invalid();
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||(long)width*height>25_000_000L)throw invalid();
                var decoded=reader.read(0);if(decoded==null)throw invalid();
                ByteArrayOutputStream out=new ByteArrayOutputStream();if(!ImageIO.write(decoded,png?"png":"jpeg",out)||out.size()>5*1024*1024)throw invalid();
                return out.toByteArray();
            } finally {reader.dispose();}
        }
    }
    private static void docx(byte[] bytes)throws Exception {
        Set<String> names=new HashSet<>();long total=0;int count=0;boolean mainType=false,rootLink=false,wordDocument=false;
        try(var zip=org.apache.commons.compress.archivers.zip.ZipFile.builder().setByteArray(bytes).get()) {
            var entries=zip.getEntries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();
                if(!zip.canReadEntryData(entry)||entry.getGeneralPurposeBit().usesEncryption())throw invalid();
                String name=entry.getName();
                if(++count>1000||!names.add(name)||name.isBlank()||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.chars().anyMatch(Character::isISOControl))throw invalid();
                for(String part:name.split("/"))if(part.equals("..")||part.equals("."))throw invalid();
                String lower=name.toLowerCase(Locale.ROOT);if(lower.contains("vbaproject")||lower.endsWith(".bin")||lower.contains("macros"))throw invalid();
                ByteArrayOutputStream expanded=new ByteArrayOutputStream();byte[] buffer=new byte[8192];long entrySize=0;int n;CRC32 crc=new CRC32();
                try(InputStream input=zip.getInputStream(entry)) {
                    while((n=input.read(buffer))!=-1){entrySize+=n;total+=n;if(entrySize>20L*1024*1024||total>64L*1024*1024)throw invalid();crc.update(buffer,0,n);expanded.write(buffer,0,n);}
                }
                long compressed=entry.getCompressedSize();if(entrySize!=entry.getSize()||crc.getValue()!=entry.getCrc()||entrySize>0&&(compressed<=0||entrySize>compressed*100L))throw invalid();
                if(lower.endsWith(".xml")||lower.endsWith(".rels")) {
                    Document xml=xml(expanded.toByteArray());
                    var relationships=xml.getElementsByTagNameNS("*","Relationship");
                    for(int i=0;i<relationships.getLength();i++) {
                        var rel=(org.w3c.dom.Element)relationships.item(i);String target=rel.getAttribute("Target");
                        String relationType=rel.getAttribute("Type").toLowerCase(Locale.ROOT);
                        if(relationType.contains("vba")||relationType.contains("macro")||rel.getAttribute("TargetMode").equalsIgnoreCase("External")||target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")||target.startsWith("//")||target.contains("\\"))throw invalid();
                        if(name.equals("_rels/.rels")&&rel.getAttribute("Type").equals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument")&&target.equals("word/document.xml"))rootLink=true;
                    }
                    if(name.equals("[Content_Types].xml")) {
                        var declarations=xml.getElementsByTagNameNS("*","*");
                        for(int i=0;i<declarations.getLength();i++){String type=((org.w3c.dom.Element)declarations.item(i)).getAttribute("ContentType").toLowerCase(Locale.ROOT);if(type.contains("macro")||type.contains("vba"))throw invalid();}
                        var types=xml.getElementsByTagNameNS("*","Override");
                        for(int i=0;i<types.getLength();i++){var type=(org.w3c.dom.Element)types.item(i);String value=type.getAttribute("ContentType");if(value.toLowerCase(Locale.ROOT).contains("macro"))throw invalid();if(type.getAttribute("PartName").equals("/word/document.xml")&&value.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"))mainType=true;}
                    }
                    if(name.equals("word/document.xml"))wordDocument=xml.getDocumentElement().getLocalName().equals("document")&&xml.getDocumentElement().getNamespaceURI().equals("http://schemas.openxmlformats.org/wordprocessingml/2006/main");
                }
            }
        }
        if(!mainType||!rootLink||!wordDocument)throw invalid();
    }
    private static Document xml(byte[] bytes)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
        return builder.parse(new ByteArrayInputStream(bytes));
    }
}
