package tv.moonshot;

public final class Channel {
  public String id, source, name, group, tvgId, url, kind;
  public String catchupMode = "", catchupSource = "";
  public int catchupDays = 0;
  public String now = "";
  public boolean favorite;
  public long position;

  public Channel(String name, String group, String tvgId, String url, String kind) {
    this.name = name;
    this.group = group;
    this.tvgId = tvgId;
    this.url = url;
    this.kind = kind;
  }
}
