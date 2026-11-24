package com.sandiv.tvtosonos;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/** Finds Sonos rooms on the Wi-Fi and tells them what to play. */
public final class Sonos {

    public static final class Room {
        public final String name;
        public final String ip;   // IP of the room's coordinator (the speaker that takes commands)
        public final String uuid; // Sonos ID, e.g. RINCON_000E58...

        Room(String name, String ip, String uuid) {
            this.name = name;
            this.ip = ip;
            this.uuid = uuid;
        }
    }

    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String GRC = "urn:schemas-upnp-org:service:GroupRenderingControl:1";
    private static final String ZGT = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";

    private Sonos() {}

    // ---------- Discovery ----------

    public static List<Room> discover(Context ctx) {
        Set<String> ips = ssdpSearch(ctx);
        for (String ip : ips) {
            try {
                List<Room> rooms = topology(ip);
                if (!rooms.isEmpty()) return rooms;
            } catch (Exception ignored) {
                // try the next speaker
            }
        }
        // Fallback: one entry per room name from each speaker's description.
        Map<String, Room> byName = new LinkedHashMap<>();
        for (String ip : ips) {
            try {
                String d = httpGet("http://" + ip + ":1400/xml/device_description.xml");
                String n = tag(d, "roomName");
                String udn = tag(d, "UDN");
                String id = udn == null ? "" : udn.replace("uuid:", "").trim();
                if (n != null && !byName.containsKey(n)) byName.put(n, new Room(unescape(n), ip, id));
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static Set<String> ssdpSearch(Context ctx) {
        Set<String> ips = new LinkedHashSet<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wm != null) {
            lock = wm.createMulticastLock("tvtosonos");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        String msg = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 1\r\n"
                + "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n";
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(700);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 3500;
            int sent = 0;
            while (System.currentTimeMillis() < end) {
                if (sent < 3) {
                    s.send(new DatagramPacket(out, out.length, group, 1900));
                    sent++;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String r = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (r.contains("ZonePlayer") || r.toLowerCase().contains("sonos")) {
                        ips.add(p.getAddress().getHostAddress());
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (lock != null) lock.release();
        }
        return ips;
    }

    /** Asks one speaker for the whole system layout and returns one entry per room/group. */
    private static List<Room> topology(String ip) throws Exception {
        String resp = soap(ip, "/ZoneGroupTopology/Control", ZGT, "GetZoneGroupState", "");
        String state = tag(resp, "ZoneGroupState");
        if (state == null) return Collections.emptyList();
        state = unescape(state);

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(state.getBytes(StandardCharsets.UTF_8)));
        NodeList groups = doc.getElementsByTagName("ZoneGroup");
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            String coordinator = g.getAttribute("Coordinator");
            String coordName = null, coordIp = null;
            List<String> others = new ArrayList<>();
            NodeList members = g.getElementsByTagName("ZoneGroupMember");
            for (int j = 0; j < members.getLength(); j++) {
                Element m = (Element) members.item(j);
                if ("1".equals(m.getAttribute("IsZoneBridge"))) continue;
                String name = m.getAttribute("ZoneName");
                if (m.getAttribute("UUID").equals(coordinator)) {
                    coordName = name;
                    coordIp = Uri.parse(m.getAttribute("Location")).getHost();
                } else if (!"1".equals(m.getAttribute("Invisible")) && !others.contains(name)) {
                    others.add(name);
                }
            }
            if (coordIp == null || coordName == null) continue;
            others.remove(coordName);
            String label = others.isEmpty() ? coordName : coordName + " + " + String.join(" + ", others);
            rooms.add(new Room(label, coordIp, coordinator));
        }
        rooms.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return rooms;
    }

    // ---------- Control ----------

    public static void play(String ip, String uri) throws IOException {
        String meta = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" "
                + "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" "
                + "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                + "<item id=\"tvtosonos\" parentID=\"0\" restricted=\"true\">"
                + "<dc:title>Google TV</dc:title>"
                + "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>"
                + "</item></DIDL-Lite>";
        soap(ip, "/MediaRenderer/AVTransport/Control", AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + escape(uri) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + escape(meta) + "</CurrentURIMetaData>");
        soap(ip, "/MediaRenderer/AVTransport/Control", AVT, "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static String state(String ip) {
        try {
            String r = soap(ip, "/MediaRenderer/AVTransport/Control", AVT, "GetTransportInfo",
                    "<InstanceID>0</InstanceID>");
            String s = tag(r, "CurrentTransportState");
            return s == null ? "" : s;
        } catch (IOException e) {
            return "";
        }
    }

    public static void stop(String ip) {
        try {
            soap(ip, "/MediaRenderer/AVTransport/Control", AVT, "Stop", "<InstanceID>0</InstanceID>");
        } catch (IOException ignored) {
        }
    }

    /** Makes a speaker follow another room's playback (Sonos grouping, kept in sync by Sonos). */
    public static void join(String memberIp, String leadUuid) throws IOException {
        soap(memberIp, "/MediaRenderer/AVTransport/Control", AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>x-rincon:" + leadUuid + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>");
    }

    /** Takes a speaker out of whatever group it's in. */
    public static void leave(String ip) {
        try {
            soap(ip, "/MediaRenderer/AVTransport/Control", AVT, "BecomeCoordinatorOfStandaloneGroup",
                    "<InstanceID>0</InstanceID>");
        } catch (IOException ignored) {
        }
    }

    /** Changes the volume of the whole group (all rooms together); falls back to one speaker. */
    public static void changeVolume(String ip, int delta) throws IOException {
        try {
            soap(ip, "/MediaRenderer/GroupRenderingControl/Control", GRC, "SetRelativeGroupVolume",
                    "<InstanceID>0</InstanceID><Adjustment>" + delta + "</Adjustment>");
        } catch (IOException e) {
            soap(ip, "/MediaRenderer/RenderingControl/Control", RC, "SetRelativeVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel><Adjustment>" + delta + "</Adjustment>");
        }
    }

    /** The TV's own IP address on the network that can reach this speaker. */
    public static String localIpFor(String speakerIp) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(new InetSocketAddress(speakerIp, 1400));
            return s.getLocalAddress().getHostAddress();
        }
    }

    // ---------- Helpers ----------

    private static String soap(String ip, String path, String service, String action, String args)
            throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":1400" + path).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(bytes);
            }
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new IOException("Sonos said no (" + code + " " + tag(resp, "errorCode") + ")");
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** Text inside the first <name>...</name>, allowing a namespace prefix such as <u:name>. */
    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + name + ">",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
