package com.caizhitong.integration.scanner;
import java.io.InputStream;
public interface VirusScanner { ScanResult scan(InputStream input,long maxBytes); }
