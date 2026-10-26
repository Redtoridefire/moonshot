package tv.moonshot;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.time.*;
import java.time.format.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;

final class XmlTv {
  static long time(String value) {
    if (value == null || value.length() < 14) return 0;
    try {
      ZoneOffset zone =
          value.substring(14).trim().isEmpty()
              ? ZoneOffset.UTC
              : ZoneOffset.of(value.substring(14).trim());
      return LocalDateTime.parse(
              value.substring(0, 14),
              DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT))
          .toInstant(zone)
          .toEpochMilli();
    } catch (RuntimeException e) {
      return 0;
    }
  }

  static int importGuide(Library library, String url, boolean allowHttp) throws Exception {
    String encrypted = library.vault.seal(url);
    long now = System.currentTimeMillis();
    SQLiteDatabase db = library.getWritableDatabase();
    try (InputStream stream = Network.fetch(url, allowHttp)) {
      XMLReader parser = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
      parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
      parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      class GuideHandler extends DefaultHandler2 {
        int count, seen;
        boolean programme;
        String channel, title = "", description = "", capture;
        long start, stop;
        StringBuilder text = new StringBuilder();

        @Override
        public void startDTD(String n, String p, String s) throws SAXException {
          throw new SAXException("DTD is not supported");
        }

        @Override
        public InputSource resolveEntity(String p, String s) throws SAXException {
          throw new SAXException("External entities are not supported");
        }

        @Override
        public void startElement(String uri, String local, String name, Attributes attrs)
            throws SAXException {
          if (Thread.currentThread().isInterrupted()) throw new SAXException("Import cancelled");
          if (name.equals("programme")) {
            if (programme || ++seen > 1000000) throw new SAXException("Invalid or oversized guide");
            programme = true;
            channel = attrs.getValue("channel");
            start = time(attrs.getValue("start"));
            stop = time(attrs.getValue("stop"));
            title = "";
            description = "";
            if (channel != null && channel.length() > 256)
              throw new SAXException("Channel identifier exceeds limit");
          } else if (programme && (name.equals("title") || name.equals("desc"))) {
            capture = name;
            text.setLength(0);
          }
        }

        @Override
        public void characters(char[] chars, int offset, int length) throws SAXException {
          if (capture != null) {
            if (text.length() + length > 16384)
              throw new SAXException("Program text exceeds limit");
            text.append(chars, offset, length);
          }
        }

        @Override
        public void endElement(String uri, String local, String name) throws SAXException {
          if (name.equals(capture)) {
            if (name.equals("title")) title = text.toString();
            else description = text.toString();
            capture = null;
          }
          if (name.equals("programme")) {
            programme = false;
            if (channel != null
                && start > 0
                && stop > start
                && stop > now - 14L * 86400000
                && start < now + 14L * 86400000) {
              ContentValues v = new ContentValues();
              v.put("channel", channel);
              v.put("start", start);
              v.put("stop", stop);
              v.put("title", title);
              v.put("description", description);
              db.insertOrThrow("programs", null, v);
              count++;
            }
          }
        }
      }
      GuideHandler handler = new GuideHandler();
      parser.setContentHandler(handler);
      parser.setEntityResolver(handler);
      parser.setErrorHandler(handler);
      parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
      db.beginTransaction();
      try {
        db.delete("programs", null, null);
        InputSource input = new InputSource(new TagBoundedInput(stream));
        input.setEncoding("UTF-8");
        parser.parse(input);
        if (handler.count == 0)
          throw new IOException("No guide programs within the retention window");
        library.setting("guideUrl", encrypted);
        library.setting("guideUpdated", Long.toString(now));
        db.setTransactionSuccessful();
        return handler.count;
      } finally {
        db.endTransaction();
      }
    }
  }

  private static final class TagBoundedInput extends FilterInputStream {
    int tagLength, quote, mode, previous, beforePrevious;
    boolean tag;
    StringBuilder prefix = new StringBuilder();

    TagBoundedInput(InputStream in) {
      super(in);
    }

    void check(int c) throws IOException {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      if (!tag && c == '<') {
        tag = true;
        tagLength = 0;
        quote = mode = previous = beforePrevious = 0;
        prefix.setLength(0);
      }
      if (!tag) return;
      if (++tagLength > 65536) throw new IOException("XML token exceeds limit");
      if (prefix.length() < 9) {
        prefix.append((char) c);
        String p = prefix.toString();
        if (p.equals("<!--")) mode = 1;
        else if (p.equals("<![CDATA[")) mode = 2;
        else if (p.equals("<?")) mode = 3;
      }
      if (mode == 1) {
        if (beforePrevious == '-' && previous == '-' && c == '>') tag = false;
      } else if (mode == 2) {
        if (beforePrevious == ']' && previous == ']' && c == '>') tag = false;
      } else if (mode == 3) {
        if (previous == '?' && c == '>') tag = false;
      } else if (quote != 0) {
        if (c == quote) quote = 0;
      } else if (c == '\'' || c == '"') quote = c;
      else if (c == '>') tag = false;
      beforePrevious = previous;
      previous = c;
    }

    @Override
    public int read() throws IOException {
      int c = in.read();
      if (c >= 0) check(c);
      return c;
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
      int n = in.read(b, offset, length);
      for (int i = 0; i < n; i++) check(b[offset + i] & 255);
      return n;
    }
  }
}
