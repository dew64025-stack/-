package com.caizhitong.material.files;

import com.caizhitong.common.BusinessException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileFormatTest {
    private static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    @Test void imagesKeepDimensionsButDiscardExifAndRejectTypeAndPixelSpoofing()throws Exception {
        var out=new ByteArrayOutputStream();var image=new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image,"jpeg",out);byte[] jpg=out.toByteArray();
        byte[] exif="Exif\0\0private-location-marker".getBytes(StandardCharsets.US_ASCII);var source=new ByteArrayOutputStream();source.write(jpg,0,2);source.write(new byte[]{(byte)255,(byte)225,0,(byte)(exif.length+2)});source.write(exif);source.write(jpg,2,jpg.length-2);
        byte[] cleaned=FileFormat.validateAndSanitize(source.toByteArray(),"image/jpeg","avatar");
        assertFalse(new String(cleaned,StandardCharsets.ISO_8859_1).contains("private-location-marker"));assertEquals(3,javax.imageio.ImageIO.read(new ByteArrayInputStream(cleaned)).getWidth());
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(jpg,"image/png","avatar"));
        out.reset();javax.imageio.ImageIO.write(image,"png",out);byte[] png=out.toByteArray();java.nio.ByteBuffer.wrap(png).putInt(16,5001).putInt(20,5000);
        CRC32 crc=new CRC32();crc.update(png,12,17);java.nio.ByteBuffer.wrap(png).putInt(29,(int)crc.getValue());
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(png,"image/png","avatar"));
    }
    @Test void realPdfStructureAndFiveHundredPageBoundaryAreChecked()throws Exception {
        for(int count:new int[]{500,501})try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
            for(int i=0;i<count;i++)pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());var out=new ByteArrayOutputStream();pdf.save(out);
            if(count==500)assertArrayEquals(out.toByteArray(),FileFormat.validateAndSanitize(out.toByteArray(),"application/pdf","material"));
            else assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(out.toByteArray(),"application/pdf","material"));
        }
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize("%PDF-1.7\nnot-a-document".getBytes(StandardCharsets.US_ASCII),"application/pdf","material"));
    }
    @Test void docxRejectsTraversalMacrosExternalEntitiesBombsAndCentralEncryption()throws Exception {
        Map<String,String> base=base();assertArrayEquals(zip(base),FileFormat.validateAndSanitize(zip(base),DOCX,"material"));
        for(var extra:List.of(Map.of("../evil.xml","<a/>"),Map.of("word/vbaProject.bin","macro"),Map.of("word/extra.xml","<!DOCTYPE a [<!ENTITY e SYSTEM 'file:///should-not-read'>]><a>&e;</a>"),Map.of("word/_rels/document.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship TargetMode='External' Target='https://evil.test/a'/></Relationships>"),Map.of("word/huge.xml"," ".repeat(1_000_000)))) {
            var fixture=new LinkedHashMap<>(base);fixture.putAll(extra);assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(zip(fixture),DOCX,"material"));
        }
        var tooMany=new LinkedHashMap<>(base);for(int i=0;i<998;i++)tooMany.put("extra"+i,"x");assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(zip(tooMany),DOCX,"material"));
        byte[] encrypted=zip(base);for(int i=0;i<encrypted.length-10;i++)if(encrypted[i]==80&&encrypted[i+1]==75&&encrypted[i+2]==1&&encrypted[i+3]==2)encrypted[i+8]|=1;
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(encrypted,DOCX,"material"),"Central directory encrypted flags must be rejected too");
    }
    @Test void docxRejectsRenamedMacroPartAndCorruptCentralCrc()throws Exception {
        var macro=new LinkedHashMap<>(base());macro.put("[Content_Types].xml",macro.get("[Content_Types].xml").replace("</Types>","<Default Extension='dat' ContentType='application/vnd.ms-office.vbaProject'/></Types>"));
        macro.put("word/hidden.dat","macro");macro.put("word/_rels/document.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Type='http://schemas.microsoft.com/office/2006/relationships/vbaProject' Target='hidden.dat'/></Relationships>");
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(zip(macro),DOCX,"material"));
        byte[] corrupt=zip(base());for(int i=0;i<corrupt.length-20;i++)if(corrupt[i]==80&&corrupt[i+1]==75&&corrupt[i+2]==1&&corrupt[i+3]==2)Arrays.fill(corrupt,i+16,i+20,(byte)0);
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(corrupt,DOCX,"material"));
    }
    @Test void rosterOnlyAcceptsStrictUtf8WithControlledHeader(){
        byte[] csv="studentNumber,college,grade,status\n".getBytes(StandardCharsets.UTF_8);assertArrayEquals(csv,FileFormat.validateAndSanitize(csv,"text/csv","student_roster"));
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(new byte[]{(byte)255},"text/csv","student_roster"));
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize("user,password\n".getBytes(StandardCharsets.UTF_8),"text/csv","student_roster"));
        assertThrows(BusinessException.class,()->FileFormat.validateAndSanitize(csv,"text/csv","material"));
    }
    static Map<String,String> base(){var result=new LinkedHashMap<String,String>();
        result.put("[Content_Types].xml","<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/></Types>");
        result.put("_rels/.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>");
        result.put("word/document.xml","<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body/></w:document>");return result;
    }
    static byte[] zip(Map<String,String> entries)throws IOException {var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();}
}
