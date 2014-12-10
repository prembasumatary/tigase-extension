package org.kontalk.xmppserver;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.util.encoders.Hex;
import org.kontalk.xmppserver.pgp.PGPUserID;
import org.kontalk.xmppserver.pgp.PGPUtils;
import org.kontalk.xmppserver.registration.DataVerificationRepository;
import org.kontalk.xmppserver.registration.PhoneNumberVerificationProvider;

import org.kontalk.xmppserver.registration.VerificationRepository;
import tigase.db.NonAuthUserRepository;
import tigase.db.TigaseDBException;
import tigase.form.Field;
import tigase.form.Form;
import tigase.server.Iq;
import tigase.server.Packet;
import tigase.stats.StatisticsList;
import tigase.util.Base64;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPException;
import tigase.xmpp.XMPPProcessor;
import tigase.xmpp.XMPPProcessorIfc;
import tigase.xmpp.XMPPResourceConnection;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;


/**
 * jabber:iq:register plugin for Kontalk.
 * Inspired by the jabber:iq:register Tigase plugin.
 * @author Daniele Ricci
 */
public class KontalkIqRegister extends XMPPProcessor implements XMPPProcessorIfc {

    private static final String[][] ELEMENTS = {Iq.IQ_QUERY_PATH};
    public static final String ID = "kontalk:jabber:iq:register";

    private static Logger log = Logger.getLogger(KontalkIqRegister.class.getName());
    private static final String[] XMLNSS = {"jabber:iq:register"};

    // form XPath and xmlns
    private static final String IQ_FORM_ELEM_NAME = "x" ;
    private static final String IQ_FORM_XMLNS = "jabber:x:data";
    private static final String IQ_FORM_KONTALK_CODE_XMLNS = "http://kontalk.org/protocol/register#code";

    // form fields
    private static final String FORM_FIELD_PHONE = "phone";
    private static final String FORM_FIELD_CODE = "code";
    private static final String FORM_FIELD_PUBKEY = "publickey";

    private static final Element[] FEATURES = {new Element("register", new String[]{"xmlns"},
            new String[]{"http://jabber.org/features/iq-register"})};
    private static final Element[] DISCO_FEATURES = {new Element("feature", new String[]{"var"},
            new String[]{"jabber:iq:register"})};

    private static final String ERROR_INVALID_CODE = "Invalid verification code.";
    private static final String ERROR_MALFORMED_REQUEST = "Please provide either a phone number or a public key and a verification code.";

    // TODO support for multiple domains
    private String serverFingerprint;
    private VerificationRepository repo;
    private PhoneNumberVerificationProvider provider;

    private long statsRegistrationAttempts;
    private long statsRegisteredUsers;
    private long statsInvalidRegistrations;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void init(Map<String, Object> settings) throws TigaseDBException {
        serverFingerprint = (String) settings.get("fingerprint");

        // database parameters
        String dbUri = (String) settings.get("db-uri");
        try {
            repo = new DataVerificationRepository(dbUri);
        }
        catch (ClassNotFoundException e) {
            throw new TigaseDBException("Repository class not found (uri=" + dbUri + ")", e);
        }
        catch (InstantiationException e) {
            throw new TigaseDBException("Unable to create instance for repository (uri=" + dbUri + ")", e);
        }
        catch (SQLException e) {
            throw new TigaseDBException("SQL exception (uri=" + dbUri + ")", e);
        }
        catch (IllegalAccessException e) {
            throw new TigaseDBException("Unknown error (uri=" + dbUri + ")", e);
        }

        // registration provider
        String providerClassName = (String) settings.get("provider");
        try {
            @SuppressWarnings("unchecked")
            Class<? extends PhoneNumberVerificationProvider> providerClass =
                    (Class<? extends PhoneNumberVerificationProvider>) Class.forName(providerClassName);
            provider = providerClass.newInstance();
            provider.init(settings);
        }
        catch (ClassNotFoundException e) {
            throw new TigaseDBException("Provider class not found: " + providerClassName);
        }
        catch (InstantiationException e) {
            throw new TigaseDBException("Unable to create provider instance for " + providerClassName);
        }
        catch (IllegalAccessException e) {
            throw new TigaseDBException("Unable to create provider instance for " + providerClassName);
        }
    }

    @Override
    public void process(Packet packet, XMPPResourceConnection session, NonAuthUserRepository repo, Queue<Packet> results, Map<String, Object> settings) throws XMPPException {
        if (log.isLoggable(Level.FINEST)) {
            log.finest("Processing packet: " + packet.toString());
        }
        if (session == null) {
            if (log.isLoggable(Level.FINEST)) {
                log.finest("Session is null, ignoring");
            }

            return;
        }

        BareJID id = session.getDomainAsJID().getBareJID();

        if (packet.getStanzaTo() != null) {
            id = packet.getStanzaTo().getBareJID();
        }
        try {

            if ((packet.getPacketFrom() != null) && packet.getPacketFrom().equals(session.getConnectionId())
                    && (!session.isAuthorized() || (session.isUserId(id) || session.isLocalDomain(id.toString(), false)))) {

                Element request = packet.getElement();

                if (!session.isAuthorized()) {
                    if (!session.getDomain().isRegisterEnabled()) {
                        results.offer(Authorization.NOT_ALLOWED.getResponseMessage(packet,
                                "Registration is not allowed for this domain.", true));
                        ++statsInvalidRegistrations;
                        return;
                    }
                }

                StanzaType type = packet.getType();

                switch (type) {
                    case set:

                        Element query = request.getChild(Iq.QUERY_NAME, XMLNSS[0]);
                        Element formElement = (query != null) ? query.getChild(IQ_FORM_ELEM_NAME, IQ_FORM_XMLNS) : null;
                        if (formElement != null) {
                            Form form = new Form(formElement);

                            // phone number
                            String phone = form.getAsString(FORM_FIELD_PHONE);
                            if (phone != null) {
                                Packet response = registerPhone(session, packet, phone);
                                statsRegistrationAttempts++;
                                results.offer(response);
                                break;
                            }

                            // verification code + public key
                            String code = form.getAsString(FORM_FIELD_CODE);
                            String publicKey = form.getAsString(FORM_FIELD_PUBKEY);
                            if (code != null && publicKey != null) {

                                // load public key
                                byte[] publicKeyData = Base64.decode(publicKey);
                                PGPPublicKey key = loadPublicKey(publicKeyData);
                                // verify user id
                                BareJID jid = verifyPublicKey(session, key);

                                if (verifyCode(jid, code)) {
                                    byte[] signedKey = signPublicKey(session, publicKeyData);

                                    Packet response = register(session, packet, jid, key.getFingerprint(), signedKey);
                                    statsRegisteredUsers++;
                                    results.offer(response);
                                    break;
                                }
                                else {
                                    // invalid verification code
                                    results.offer(Authorization.BAD_REQUEST.getResponseMessage(packet, ERROR_INVALID_CODE, true));
                                    break;
                                }
                            }

                        }

                        // bad request
                        results.offer(Authorization.BAD_REQUEST.getResponseMessage(packet, ERROR_MALFORMED_REQUEST, true));
                        break;

                    case get: {
                        // TODO instructions form
                        break;
                    }
                    default:
                        results.offer(Authorization.BAD_REQUEST.getResponseMessage(packet, "Message type is incorrect", true));

                        break;
                }
            }
        }
        catch (NotAuthorizedException e) {
            results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
                    "You are not authorized to change registration settings.\n" + e.getMessage(), true));
        }
        catch (TigaseDBException e) {
            log.warning("Database problem: " + e);
            results.offer(Authorization.INTERNAL_SERVER_ERROR.getResponseMessage(packet,
                    "Database access problem, please contact administrator.", true));
        }
        // generated from PGP
        catch (IOException e) {
            log.warning("Unknown error: " + e);
            results.offer(Authorization.INTERNAL_SERVER_ERROR.getResponseMessage(packet,
                    "Internal PGP error. Please contact administrator.", true));
        }
        catch (PGPException e) {
            log.warning("PGP problem: " + e);
            results.offer(Authorization.BAD_REQUEST.getResponseMessage(packet,
                    "Invalid public key.", true));
        }
    }

    private Packet registerPhone(XMPPResourceConnection session, Packet packet, String phoneInput) throws PacketErrorTypeException, TigaseDBException {
        String phone;
        try {
            phone = formatPhoneNumber(phoneInput);
        }
        catch (NumberParseException e) {
            // bad number
            statsInvalidRegistrations++;
            log.log(Level.INFO, "Invalid phone number: {0}", phoneInput);
            return Authorization.BAD_REQUEST.getResponseMessage(packet, "Bad phone number.", true);
        }

        log.log(Level.FINEST, "Registering phone number: {0}", phone);

        // generate userid from phone number
        String userId = generateUserId(phone);
        BareJID jid = BareJID.bareJIDInstanceNS(userId, session.getDomainAsJID().getDomain());

        // generate verification code
        String code;
        try {
            code = generateVerificationCode(jid);
        }
        catch (VerificationRepository.AlreadyRegisteredException e) {
            // throttling registrations
            statsInvalidRegistrations++;
            log.log(Level.INFO, "Throttling registration for: {0}", jid);
            return packet.errorResult("wait",
                    Authorization.SERVICE_UNAVAILABLE.getErrorCode(),
                    Authorization.SERVICE_UNAVAILABLE.getCondition(),
                    "Too many attempts.",
                    true);
        }

        // send SMS to phone number
        try {
            provider.sendVerificationCode(phone, code);

            return packet.okResult(prepareSMSResponseForm(provider.getSenderId()), 0);
        }
        catch (IOException e) {
            // throttling registrations
            statsInvalidRegistrations++;
            log.log(Level.WARNING, "Failed to send verification code for: {0}", jid);
            return Authorization.NOT_ACCEPTABLE.getResponseMessage(packet, "Unable to send SMS.", true);
        }
    }

    private Element prepareSMSResponseForm(String from) {
        Element query = new Element("query", new String[] { "xmlns" }, XMLNSS);
        query.addChild(new Element("instructions", provider.getAckInstructions()));
        Form form = new Form("form", null, null);

        form.addField(Field.fieldHidden("FORM_TYPE", XMLNSS[0]));
        form.addField(Field.fieldTextSingle("from", from, "SMS sender"));

        query.addChild(form.getElement());
        return query;
    }

    private Packet register(XMPPResourceConnection session, Packet packet, BareJID jid, byte[] fingerprint, byte[] publicKey)
            throws TigaseDBException {
        KontalkAuth.setUserFingerprint(session, jid, Hex.toHexString(fingerprint).toUpperCase());
        return packet.okResult(prepareRegisteredResponseForm(publicKey), 0);
    }

    private Element prepareRegisteredResponseForm(byte[] publicKey) {
        Element query = new Element("query", new String[] { "xmlns" }, XMLNSS);
        Form form = new Form("form", null, null);

        form.addField(Field.fieldHidden("FORM_TYPE", IQ_FORM_KONTALK_CODE_XMLNS));
        form.addField(Field.fieldTextSingle("publickey", Base64.encode(publicKey), "Signed public key"));

        query.addChild(form.getElement());
        return query;
    }

    private String formatPhoneNumber(String phoneInput) throws NumberParseException {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        Phonenumber.PhoneNumber phone = util.parse(phoneInput, null);
        return util.format(phone, PhoneNumberUtil.PhoneNumberFormat.E164);
    }

    private String generateUserId(String phone) {
        return sha1(phone);
    }

    private String generateVerificationCode(BareJID jid)
            throws VerificationRepository.AlreadyRegisteredException, TigaseDBException {
        return repo.generateVerificationCode(jid);
    }

    private BareJID parseUserID(PGPPublicKey publicKey) throws PGPException {
        PGPUserID uid = PGPUtils.parseUserID(publicKey);
        if (uid == null)
            throw new PGPException("Invalid user id");
        return BareJID.bareJIDInstanceNS(uid.getEmail());
    }

    private PGPPublicKey loadPublicKey(byte[] publicKeyData) throws IOException, PGPException {
        return PGPUtils.getMasterKey(publicKeyData);
    }

    private BareJID verifyPublicKey(XMPPResourceConnection session, PGPPublicKey publicKey) throws PGPException {
        BareJID jid = parseUserID(publicKey);
        if (!session.getDomainAsJID().toString().equalsIgnoreCase(jid.getDomain()))
            throw new PGPException("Invalid email identifier");

        // TODO import key into gpg for advanced verification

        return jid;
    }

    private boolean verifyCode(BareJID jid, String code) throws TigaseDBException {
        return repo.verifyCode(jid, code);
    }

    private byte[] signPublicKey(XMPPResourceConnection session, byte[] publicKeyData) throws IOException, PGPException {
        String domain = session.getDomainAsJID().toString();
        KontalkKeyring keyring = KontalkKeyring.getInstance(domain, serverFingerprint);
        return keyring.signKey(publicKeyData);
    }

    private static String sha1(String text) {
        try {
            MessageDigest md;
            md = MessageDigest.getInstance("SHA-1");
            md.update(text.getBytes(), 0, text.length());

            byte[] digest = md.digest();
            return Hex.toHexString(digest);
        }
        catch (NoSuchAlgorithmException e) {
            // no SHA-1?? WWWHHHHAAAAAATTTT???!?!?!?!?!
            throw new RuntimeException("no SHA-1 available. What the crap of a runtime do you have?");
        }
    }

    @Override
    public Element[] supDiscoFeatures(XMPPResourceConnection session) {
        if (log.isLoggable(Level.FINEST) && (session != null)) {
            log.finest("VHostItem: " + session.getDomain());
        }
        if ((session != null) && session.getDomain().isRegisterEnabled()) {
            return DISCO_FEATURES;
        } else {
            return null;
        }
    }

    @Override
    public String[][] supElementNamePaths() {
        return ELEMENTS;
    }

    @Override
    public String[] supNamespaces() {
        return XMLNSS;
    }

    @Override
    public Element[] supStreamFeatures(XMPPResourceConnection session) {
        if (log.isLoggable(Level.FINEST) && (session != null)) {
            log.finest("VHostItem: " + session.getDomain());
        }
        if ((session != null) && session.getDomain().isRegisterEnabled()) {
            return FEATURES;
        } else {
            return null;
        }
    }

    @Override
    public void getStatistics(StatisticsList list) {
        super.getStatistics(list);
        list.add(getComponentInfo().getName(), "Registration attempts", statsRegistrationAttempts, Level.INFO);
        list.add(getComponentInfo().getName(), "Registered users", statsRegisteredUsers, Level.INFO);
        list.add(getComponentInfo().getName(), "Invalid registrations", statsInvalidRegistrations, Level.INFO);
    }

}
