package davmail.ldap;

import com.sun.jndi.ldap.Ber;
import com.sun.jndi.ldap.BerDecoder;
import com.sun.jndi.ldap.BerEncoder;
import davmail.AbstractConnection;
import davmail.Settings;
import davmail.exchange.ExchangeSessionFactory;
import davmail.tray.DavGatewayTray;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Handle a caldav connection.
 */
public class LdapConnection extends AbstractConnection {
    /**
     * Davmail base context
     */
    static final String BASE_CONTEXT = "ou=people";
    /**
     * Exchange to LDAP attribute map
     */
    static final HashMap<String, String> ATTRIBUTE_MAP = new HashMap<String, String>();
    static {
        ATTRIBUTE_MAP.put("uid", "AN");
        ATTRIBUTE_MAP.put("mail", "EM");
        ATTRIBUTE_MAP.put("displayName", "DN");
        ATTRIBUTE_MAP.put("telephoneNumber", "PH");
        ATTRIBUTE_MAP.put("l", "OFFICE");
        ATTRIBUTE_MAP.put("company", "CP");
        ATTRIBUTE_MAP.put("title", "TL");

        ATTRIBUTE_MAP.put("cn", "DN");
        ATTRIBUTE_MAP.put("givenName", "first");
        ATTRIBUTE_MAP.put("initials", "initials");
        ATTRIBUTE_MAP.put("sn", "last");
        ATTRIBUTE_MAP.put("street", "street");
        ATTRIBUTE_MAP.put("st", "state");
        ATTRIBUTE_MAP.put("postalCode", "zip");
        ATTRIBUTE_MAP.put("c", "country");
        ATTRIBUTE_MAP.put("departement", "department");
        ATTRIBUTE_MAP.put("mobile", "mobile");
    }

    /**
     * LDAP to Exchange Criteria Map
     */
    static final HashMap<String, String> CRITERIA_MAP = new HashMap<String, String>();
    static {
        // assume mail starts with firstname
        CRITERIA_MAP.put("mail", "FN");
        CRITERIA_MAP.put("displayname", "DN");
        CRITERIA_MAP.put("cn", "DN");
        CRITERIA_MAP.put("givenname", "FN");
        CRITERIA_MAP.put("sn", "LN");
        CRITERIA_MAP.put("title", "TL");
        CRITERIA_MAP.put("company", "CP");
        CRITERIA_MAP.put("o", "CP");
        CRITERIA_MAP.put("l", "OF");
        CRITERIA_MAP.put("department", "DP");
    }

    // LDAP version
    static final int LDAP_VERSION2 = 0x02;
    static final int LDAP_VERSION3 = 0x03;

    // LDAP request operations
    static final int LDAP_REQ_BIND = 0x60;
    static final int LDAP_REQ_SEARCH = 99;
    static final int LDAP_REQ_UNBIND = 0x42;

    // LDAP response operations
    static final int LDAP_REP_BIND = 0x61;
    static final int LDAP_REP_SEARCH = 0x64;
    static final int LDAP_REP_RESULT = 0x65;

    // LDAP return codes
    static final int LDAP_OTHER = 80;
    static final int LDAP_SUCCESS = 0;
    static final int LDAP_SIZE_LIMIT_EXCEEDED = 4;
    static final int LDAP_INVALID_CREDENTIALS = 49;


    static final int LDAP_FILTER_OR = 0xa1;

    // LDAP filter operators (only LDAP_FILTER_SUBSTRINGS is supported)
    static final int LDAP_FILTER_SUBSTRINGS = 0xa4;
    static final int LDAP_FILTER_GE = 0xa5;
    static final int LDAP_FILTER_LE = 0xa6;
    static final int LDAP_FILTER_PRESENT = 0x87;
    static final int LDAP_FILTER_APPROX = 0xa8;

    // LDAP filter mode (only startsWith supported by galfind)
    static final int LDAP_SUBSTRING_INITIAL = 0x80;
    static final int LDAP_SUBSTRING_ANY = 0x81;
    static final int LDAP_SUBSTRING_FINAL = 0x82;

    // BER data types
    static final int LBER_ENUMERATED = 0x0a;
    static final int LBER_SET = 0x31;
    static final int LBER_SEQUENCE = 0x30;

    // LDAP search scope
    static final int SCOPE_BASE_OBJECT = 0;
    static final int SCOPE_ONE_LEVEL = 1;
    static final int SCOPE_SUBTREE = 2;

    /**
     * raw connection inputStream
     */
    protected InputStream is;

    /**
     * reusable BER encoder
     */
    protected BerEncoder responseBer = new BerEncoder();

    /**
     * Current LDAP version (used for String encoding)
     */
    int ldapVersion = LDAP_VERSION3;

    // Initialize the streams and start the thread
    public LdapConnection(String name, Socket clientSocket) {
        super(name + "-" + clientSocket.getPort(), clientSocket);
        try {
            is = new BufferedInputStream(client.getInputStream());
            os = new BufferedOutputStream(client.getOutputStream());
        } catch (IOException e) {
            close();
            DavGatewayTray.error("Exception while getting socket streams", e);
        }
    }

    protected boolean isLdapV3() {
        return ldapVersion == LDAP_VERSION3;
    }

    public void run() {
        byte inbuf[] = new byte[2048];   // Buffer for reading incoming bytes
        int bytesread;  // Number of bytes in inbuf
        int bytesleft;  // Number of bytes that need to read for completing resp
        int br;         // Temp; number of bytes read from stream
        int offset;     // Offset of where to store bytes in inbuf
        int seqlen;     // Length of ASN sequence
        int seqlenlen;  // Number of sequence length bytes
        boolean eos;    // End of stream
        BerDecoder reqBer;    // Decoder for ASN.1 BER data from inbuf

        try {
            while (true) {
                offset = 0;
                seqlen = 0;
                seqlenlen = 0;


                // check that it is the beginning of a sequence
                bytesread = is.read(inbuf, offset, 1);
                if (bytesread < 0) {
                    break; // EOF
                }

                if (inbuf[offset++] != (Ber.ASN_SEQUENCE | Ber.ASN_CONSTRUCTOR))
                    continue;

                // get length of sequence
                bytesread = is.read(inbuf, offset, 1);
                if (bytesread < 0)
                    break; // EOF
                seqlen = inbuf[offset++];

                // if high bit is on, length is encoded in the
                // subsequent length bytes and the number of length bytes
                // is equal to & 0x80 (i.e. length byte with high bit off).
                if ((seqlen & 0x80) == 0x80) {
                    seqlenlen = seqlen & 0x7f;  // number of length bytes

                    bytesread = 0;
                    eos = false;

                    // Read all length bytes
                    while (bytesread < seqlenlen) {
                        br = is.read(inbuf, offset + bytesread,
                                seqlenlen - bytesread);
                        if (br < 0) {
                            eos = true;
                            break; // EOF
                        }
                        bytesread += br;
                    }

                    // end-of-stream reached before length bytes are read
                    if (eos)
                        break;  // EOF

                    // Add contents of length bytes to determine length
                    seqlen = 0;
                    for (int i = 0; i < seqlenlen; i++) {
                        seqlen = (seqlen << 8) + (inbuf[offset + i] & 0xff);
                    }
                    offset += bytesread;
                }

                // read in seqlen bytes
                bytesleft = seqlen;
                if ((offset + bytesleft) > inbuf.length) {
                    byte nbuf[] = new byte[offset + bytesleft];
                    System.arraycopy(inbuf, 0, nbuf, 0, offset);
                    inbuf = nbuf;
                }
                while (bytesleft > 0) {
                    bytesread = is.read(inbuf, offset, bytesleft);
                    if (bytesread < 0)
                        break; // EOF
                    offset += bytesread;
                    bytesleft -= bytesread;
                }

                handleRequest(new BerDecoder(inbuf, 0, offset));
            }
        } catch (SocketTimeoutException e) {
            DavGatewayTray.debug("Closing connection on timeout");
        } catch (IOException e) {
            DavGatewayTray.error(e);
        } finally {
            close();
        }
        DavGatewayTray.resetIcon();
    }

    protected void handleRequest(BerDecoder reqBer) throws IOException {
        int currentMessageId = 0;
        try {
            reqBer.parseSeq(null);
            currentMessageId = reqBer.parseInt();
            int requestOperation = reqBer.parseSeq(null);

            //Ber.dumpBER(System.out, "request\n", inbuf, 0, offset);

            if (requestOperation == LDAP_REQ_BIND) {
                int ldapVersion = reqBer.parseInt();
                String userName = reqBer.parseString(ldapVersion == LDAP_VERSION3);
                String password = reqBer.parseStringWithTag(Ber.ASN_CONTEXT, ldapVersion == LDAP_VERSION3, null);

                if (userName.length() > 0 && password.length() > 0) {
                    DavGatewayTray.debug("LDAP_REQ_BIND "+userName);
                    try {
                        session = ExchangeSessionFactory.getInstance(userName, password);
                        sendClient(currentMessageId, LDAP_REP_BIND, LDAP_SUCCESS, "");
                    } catch (IOException e) {
                        sendClient(currentMessageId, LDAP_REP_BIND, LDAP_INVALID_CREDENTIALS, "");
                    }
                } else {
                    DavGatewayTray.debug("LDAP_REQ_BIND anonymous"+userName);
                    // anonymous bind
                    sendClient(currentMessageId, LDAP_REP_BIND, LDAP_SUCCESS, "");
                }

            } else if (requestOperation == LDAP_REQ_UNBIND) {
                DavGatewayTray.debug("LDAP_REQ_UNBIND");
                if (session != null) {
                    ExchangeSessionFactory.close(session);
                    session = null;
                }
            } else if (requestOperation == LDAP_REQ_SEARCH) {
                String dn = reqBer.parseString(isLdapV3());
                int scope = reqBer.parseEnumeration();
                /*int deref =*/ reqBer.parseEnumeration();
                int sizeLimit = reqBer.parseInt();
                if (sizeLimit > 100 || sizeLimit == 0) {
                    sizeLimit = 100;
                }
                /* int timelimit = */reqBer.parseInt();
                /* boolean attrsOnly = */reqBer.parseBoolean();
                int size = 0;
                DavGatewayTray.debug("LDAP_REQ_SEARCH base="+dn+" scope: "+scope+" sizelimit: "+sizeLimit);

                if (scope == SCOPE_BASE_OBJECT) {
                    if ("".equals(dn)) {
                        size = 1;
                        sendRootDSE(currentMessageId);
                    }
                    if (BASE_CONTEXT.equals(dn)) {
                        size = 1;
                        // root
                        sendBaseContext(currentMessageId);
                    } else if (dn.startsWith("uid=") && dn.indexOf(',') > 0 && session != null) {
                        // single user request
                        String uid = dn.substring("uid=".length(), dn.indexOf(','));
                        Map<String, Map<String, String>> persons = session.galFind("AN", uid);
                        size = persons.size();
                        sendPersons(currentMessageId, persons);
                    }

                } else if (BASE_CONTEXT.equalsIgnoreCase(dn) && session != null) {

                    Map<String, String> criteria = parseFilter(reqBer);

                    Map<String, Map<String, String>> persons = new HashMap<String, Map<String, String>>();
                    if ("*".equals(criteria.get("objectclass"))) {
                        // full search
                        for (char c = 'A'; c < 'Z'; c++) {
                            if (persons.size() < sizeLimit) {
                                for (Map<String, String> person : session.galFind("AN", String.valueOf(c)).values()) {
                                    persons.put(person.get("AN"), person);
                                    if (persons.size() == sizeLimit) {
                                        break;
                                    }
                                }
                            }
                            if (persons.size() == sizeLimit) {
                                break;
                            }
                        }
                    } else {
                        for (Map.Entry<String, String> entry : criteria.entrySet()) {
                            if (persons.size() < sizeLimit) {
                                for (Map<String, String> person : session.galFind(entry.getKey(), entry.getValue()).values()) {
                                    persons.put(person.get("AN"), person);
                                    if (persons.size() == sizeLimit) {
                                        break;
                                    }
                                }
                            }
                            if (persons.size() == sizeLimit) {
                                break;
                            }
                        }
                    }

                    size = persons.size();
                    DavGatewayTray.debug("LDAP_REQ_SEARCH found "+size+" results");
                    sendPersons(currentMessageId, persons);
                    DavGatewayTray.debug("LDAP_REQ_SEARCH end");
                }

                if (size == sizeLimit) {
                    sendClient(currentMessageId, LDAP_REP_RESULT, LDAP_SIZE_LIMIT_EXCEEDED, "");
                } else {
                    sendClient(currentMessageId, LDAP_REP_RESULT, LDAP_SUCCESS, "");
                }
            } else {
                DavGatewayTray.debug("Unsupported operation: "+requestOperation);
                sendClient(currentMessageId, LDAP_REP_RESULT, LDAP_OTHER, "Unsupported operation");
            }
        } catch (IOException e) {
            try {
                sendErr(currentMessageId, LDAP_REP_RESULT, e);
            } catch (IOException e2) {
                DavGatewayTray.debug("Exception sending error to client", e2);
            }
            throw e;
        }
    }

    protected Map<String, String> parseFilter(BerDecoder reqBer) throws IOException {
        Map<String, String> criteria = new HashMap<String, String>();
        if (reqBer.peekByte() == LDAP_FILTER_PRESENT) {
            String attributeName = reqBer.parseStringWithTag(LDAP_FILTER_PRESENT, isLdapV3(), null).toLowerCase();
            if ("objectclass".equals(attributeName)) {
                criteria.put(attributeName, "*");
            } else {
                DavGatewayTray.warn("Unsupported filter");
            }
        } else {
            int[] seqSize = new int[1];
            int ldapFilterType = reqBer.parseSeq(seqSize);
            int end = reqBer.getParsePosition() + seqSize[0];
            if (ldapFilterType == LDAP_FILTER_OR) {
                while (reqBer.getParsePosition() < end && reqBer.bytesLeft() > 0) {
                    int ldapFilterOperator = reqBer.parseSeq(null);
                    if (ldapFilterOperator == LDAP_FILTER_SUBSTRINGS) {
                        parseSimpleFilter(reqBer, criteria);
                    }
                }
                // simple filter
            } else if (ldapFilterType == LDAP_FILTER_SUBSTRINGS) {
                parseSimpleFilter(reqBer, criteria);
            } else {
                 DavGatewayTray.warn("Unsupported filter");
            }
        }
        return criteria;
    }

    protected void parseSimpleFilter(BerDecoder reqBer, Map<String, String> criteria) throws IOException {
        String attributeName = reqBer.parseString(isLdapV3()).toLowerCase();
        /*LBER_SEQUENCE*/
        reqBer.parseSeq(null);
        int ldapFilterMode = reqBer.peekByte();
        String value = reqBer.parseStringWithTag(ldapFilterMode, isLdapV3(), null);
        String exchangeAttributeName = CRITERIA_MAP.get(attributeName);
        if (exchangeAttributeName != null) {
            criteria.put(exchangeAttributeName, value);
        } else {
            DavGatewayTray.warn("Unsupported filter attribute: " + attributeName);
        }
    }

    /**
     * Convert to LDAP attributes and send entry
     * @param currentMessageId current Message Id
     * @param persons persons Map
     * @throws IOException on error
     */
    protected void sendPersons(int currentMessageId, Map<String, Map<String, String>> persons) throws IOException {
        for (Map<String, String> person : persons.values()) {
            // add detailed information, only if few results
            if (persons.size() <=10) {
              session.galLookup(person);
            }

            Map<String, Object> ldapPerson = new HashMap<String, Object>();
            for (Map.Entry<String, String> entry : ATTRIBUTE_MAP.entrySet()) {
                String ldapAttribute = entry.getKey();
                String exchangeAttribute = entry.getValue();
                String value = person.get(exchangeAttribute);
                if (value != null) {
                    ldapPerson.put(ldapAttribute, value);
                }
            }

            sendEntry(currentMessageId, "uid=" + ldapPerson.get("uid") + "," + BASE_CONTEXT, ldapPerson);
        }

    }
    /**
     * Send Root DSE
     * @param currentMessageId current message id
     * @throws IOException on error
     */
    protected void sendRootDSE(int currentMessageId) throws IOException {
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put("objectClass", "top");
        attributes.put("namingContexts", BASE_CONTEXT);
        sendEntry(currentMessageId, "Root DSE", attributes);
    }

    /**
     * Send Base Context
     * @param currentMessageId current message id
     * @throws IOException on error
     */
    protected void sendBaseContext(int currentMessageId) throws IOException {
        List<String> objectClasses = new ArrayList<String>();
        objectClasses.add("top");
        objectClasses.add("organizationalUnit");
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put("objectClass", objectClasses);
        attributes.put("description", "DavMail Gateway LDAP for " + Settings.getProperty("davmail.url"));
        sendEntry(currentMessageId, BASE_CONTEXT, attributes);
    }

    protected void sendEntry(int currentMessageId, String dn, Map<String,Object> attributes) throws IOException {
        responseBer.reset();
        responseBer.beginSeq(Ber.ASN_SEQUENCE | Ber.ASN_CONSTRUCTOR);
            responseBer.encodeInt(currentMessageId);
            responseBer.beginSeq(LDAP_REP_SEARCH);
                responseBer.encodeString(dn, isLdapV3());
                responseBer.beginSeq(LBER_SEQUENCE);
                    for (Map.Entry<String,Object> entry:attributes.entrySet()) {
                        responseBer.beginSeq(LBER_SEQUENCE);
                            responseBer.encodeString(entry.getKey(), isLdapV3());
                            responseBer.beginSeq(LBER_SET);
                                Object values = entry.getValue();
                                if (values instanceof String) {
                                    responseBer.encodeString((String)values, isLdapV3());
                                } else if (values instanceof List) {
                                    for (Object value: (List)values) {
                                        responseBer.encodeString((String) value, isLdapV3());
                                    }
                                } else {
                                    throw new IllegalArgumentException();
                                }
                            responseBer.endSeq();
                        responseBer.endSeq();
                    }
                responseBer.endSeq();
            responseBer.endSeq();
        responseBer.endSeq();
        sendResponse();
    }

    protected void sendErr(int currentMessageId, int responseOperation, Exception e) throws IOException {
        String message = e.getMessage();
        if (message == null) {
            message = e.toString();
        }
        sendClient(currentMessageId, responseOperation, LDAP_OTHER, message);
    }

    protected void sendClient(int currentMessageId, int responseOperation, int status, String message) throws IOException {
        responseBer.reset();

        responseBer.beginSeq(Ber.ASN_SEQUENCE | Ber.ASN_CONSTRUCTOR);
            responseBer.encodeInt(currentMessageId);
            responseBer.beginSeq(responseOperation);
                responseBer.encodeInt(status, LBER_ENUMERATED);
                // dn
                responseBer.encodeString("", isLdapV3());
                // error message
                responseBer.encodeString(message, isLdapV3());
            responseBer.endSeq();
        responseBer.endSeq();
       sendResponse();
    }

    protected void sendResponse() throws IOException {
        //Ber.dumpBER(System.out, ">\n", responseBer.getBuf(), 0, responseBer.getDataLen());
        os.write(responseBer.getBuf(), 0, responseBer.getDataLen());
        os.flush();
    }

}

