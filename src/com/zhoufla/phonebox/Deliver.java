package com.zhoufla.phonebox;

/** 一条待投递记录：某条短信 → 某个通道。 */
public final class Deliver {

    public long dlId;
    public long evId;
    public String ch = "";
    public long ts;
    public String peer = "";
    public String body = "";
    public String code = "";
    public int subId = -1;
    public int tries;
    public String err = "";
    public int prio;
    public String template = "";

    public SmsEvent toEvent() {
        SmsEvent e = new SmsEvent();
        e.id = evId;
        e.ts = ts;
        e.peer = peer;
        e.body = body;
        e.code = code;
        e.subId = subId;
        return e;
    }
}
