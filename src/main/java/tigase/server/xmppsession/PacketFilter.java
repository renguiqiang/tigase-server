/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.server.xmppsession;

//~--- non-JDK imports --------------------------------------------------------

import tigase.db.NonAuthUserRepository;

import tigase.server.Packet;

import tigase.sys.TigaseRuntime;

import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;
import tigase.xmpp.NoConnectionIdException;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPResourceConnection;

//~--- JDK imports ------------------------------------------------------------

import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

//~--- classes ----------------------------------------------------------------

//import static tigase.xmpp.impl.Roster.PresenceType;

/**
 * Describe class PacketFilter here.
 *
 *
 * Created: Fri Feb  2 15:08:58 2007
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class PacketFilter {

	/**
	 * Variable <code>log</code> is a class logger.
	 */
	private static final Logger log = Logger.getLogger("tigase.server.xmppsession.PacketFilter");
	private static TigaseRuntime runtime = TigaseRuntime.getTigaseRuntime();

	//~--- fields ---------------------------------------------------------------

//private RosterAbstract roster_util = RosterFactory.getRosterImplementation(true);
	private String[] AUTH_ONLY_ELEMS = { "message", "presence" };
	private String[] IGNORE_PACKETS = { "stream:features" };
	private StanzaType[] IGNORE_TYPES = { StanzaType.error };

	//~--- constructors ---------------------------------------------------------

	/**
	 * Creates a new <code>PacketFilter</code> instance.
	 *
	 */
	public PacketFilter() {}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 * @param repo
	 * @param results
	 *
	 * @return
	 */
	public boolean forward(final Packet packet, final XMPPResourceConnection session,
			final NonAuthUserRepository repo, final Queue<Packet> results) {

		// Processing of the packets which needs to be processed as quickly
		// as possible, direct presences from unsubscribed entities apparently
		// have high priority as they may come from MUC and must be delivered
		// before room history
//  if (packet.getElemName() == "presence") {
//    PresenceType pres_type = roster_util.getPresenceType(session, packet);
//    if ((prese_type == PresenceType.in_initial)
//      && (packet.getElemFrom() != null)
//      && (roster_util.isSubscribedTo(session, packet.getElemFrom())
//        || (DynamicRoster.getBuddyItem(session, settings,
//            packet.getElemFrom()) != null))) {
//    }
//  }
		return false;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 * @param repo
	 * @param results
	 *
	 * @return
	 */
	public boolean preprocess(final Packet packet, final XMPPResourceConnection session,
			final NonAuthUserRepository repo, final Queue<Packet> results) {
		for (int i = 0; i < IGNORE_PACKETS.length; i++) {
			if ((packet.getElemName() == IGNORE_PACKETS[i])
					&& (packet.getType() == IGNORE_TYPES[i])) {
				return true;
			}
		}

		if ((session == null) || session.isServerSession()) {
			return false;
		}    // end of if (session == null)

		try {

			// For all messages coming from the owner of this account set
			// proper 'from' attribute. This is actually needed for the case
			// when the user sends a message to himself.
			if (session.getConnectionId().equals(packet.getFrom())) {
				if ( !session.isAuthorized()) {

					// We allow only certain packets here...
					// For now it is simpler to disallow all messages and presences
					// packets, the rest should be bounced back anyway
					for (String elem : AUTH_ONLY_ELEMS) {
						if (packet.getElemName() == elem) {
							results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
									"You must authenticate session first, before you"
										+ " can send any message or presence packet.", true));

							if (log.isLoggable(Level.FINE)) {
								log.fine("Packet received before the session has been authenticated."
										+ "Session details: connectionId=" + session.getConnectionId()
											+ ", sessionId=" + session.getSessionId() + ", ConnectionStatus="
												+ session.getConnectionStatus() + ", packet="
													+ packet.toStringSecure());
							}

							return true;
						}
					}

					return false;
				}

				// After authentication we require resource binding packet and
				// nothing else:
				// actually according to XEP-0170: http://xmpp.org/extensions/xep-0170.html
				// stream compression might occur between authentication and resource
				// binding
				if (session.isResourceSet()
						|| packet.isXMLNS("/iq/bind", "urn:ietf:params:xml:ns:xmpp-bind")
							|| packet.isXMLNS("compress", "http://jabber.org/protocol/compress")) {
					JID from_jid = session.getJID();

					if (from_jid != null) {

						// Do not replace current settings if there is at least correct BareJID
						// already set.
						if ((packet.getStanzaFrom() == null)
								||!from_jid.getBareJID().equals(packet.getStanzaFrom().getBareJID())) {
							if (log.isLoggable(Level.FINEST)) {
								log.finest("Setting correct from attribute: " + from_jid);
							}

							// No need for the line below, initVars(...) takes care of that
							// packet.getElement().setAttribute("from", from_jid.toString());
							packet.initVars(from_jid, packet.getStanzaTo());
						} else {
							if (log.isLoggable(Level.FINEST)) {
								log.finest("Skipping setting correct from attribute: " + from_jid
										+ ", is already correct.");
							}
						}
					} else {
						log.warning("Session is authenticated but session.getJid() is empty: "
								+ packet.toStringSecure());
					}
				} else {

					// We do not accept anything without resource binding....
					results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
							"You must bind the resource first: http://www.xmpp.org/rfcs/rfc3920.html#bind",
								true));

					if (log.isLoggable(Level.INFO)) {
						log.info("Session details: connectionId=" + session.getConnectionId()
								+ ", sessionId=" + session.getSessionId() + ", ConnectionStatus="
									+ session.getConnectionStatus());
					}

					if (log.isLoggable(Level.FINEST)) {
						log.finest("Session more detais: JID=" + session.getjid());
					}

					return true;
				}
			}
		} catch (PacketErrorTypeException e) {

			// Ignore this packet
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Ignoring packet with an error to non-existen user session: "
						+ packet.toStringSecure());
			}
		} catch (Exception e) {
			log.log(Level.FINEST, "Packet preprocessing exception: ", e);

			return false;
		}    // end of try-catch

		return false;
	}

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param session
	 * @param repo
	 * @param results
	 *
	 * @return
	 */
	public boolean process(final Packet packet, final XMPPResourceConnection session,
			final NonAuthUserRepository repo, final Queue<Packet> results) {
		if (session == null) {
			return false;
		}    // end of if (session == null)

		if (log.isLoggable(Level.FINEST)) {
			log.finest("Processing packet: " + packet.toStringSecure());
		}

		try {

			// Can not forward packet if there is no destination address
			if (packet.getStanzaTo() == null) {

				// If this is simple <iq type="result"/> then ignore it
				// and consider it OK
				if (packet.getElemName().equals("iq") && (packet.getType() == StanzaType.result)) {

					// Nothing to do....
					return true;
				}

				if (log.isLoggable(Level.INFO)) {
					log.info("No 'to' address, can't deliver packet: " + packet.toString());
				}

				return false;
			}

			// Already done in forward method....
			// No need to repeat this (performance - everything counts...)
//    // For all messages coming from the owner of this account set
//    // proper 'from' attribute. This is actually needed for the case
//    // when the user sends a message to himself.
//    if (packet.getFrom() != null
//      && packet.getFrom().equals(session.getConnectionId())) {
//      packet.getElement().setAttribute("from", session.getJID());
//      log.finest("Setting correct from attribute: " + session.getJID());
//    } // end of if (packet.getFrom().equals(session.getConnectionId()))
			// String id = null;
			JID to = packet.getStanzaTo();

			if (session.isUserId(to.getBareJID())) {

				// Yes this is message to 'this' client
				if (log.isLoggable(Level.FINEST)) {
					log.finest("Yes, this is packet to 'this' client: " + to);
				}

//      Element elem = packet.getElement().clone();
//      Packet result = new Packet(elem);
//      result.setTo(session.getConnectionId(packet.getElemTo()));
//      if (log.isLoggable(Level.FINEST)) {
//        log.finest("Setting to: " + result.getTo());
//      }
//      result.setFrom(packet.getTo());
				Packet result = packet.copyElementOnly();

				result.setPacketFrom(packet.getTo());

				try {
					result.setPacketTo(session.getConnectionId(packet.getStanzaTo()));
					results.offer(result);
				} catch (NoConnectionIdException ex) {
					log.warning("Packet to the server which hasn't been properly processed: " + packet);
				}

				return true;
			}    // end of else

			BareJID from = packet.getStanzaFrom().getBareJID();

			if (session.isUserId(from)) {
				Packet result = packet.copyElementOnly();

//      String[] connIds = runtime.getConnectionIdsForJid(to);
//      if (connIds != null && connIds.length > 0) {
//        result.setTo(connIds[0]);
//      }
				results.offer(result);

				return true;
			}
		} catch (NotAuthorizedException e) {
			try {
				results.offer(Authorization.NOT_AUTHORIZED.getResponseMessage(packet,
						"You must authorize session first.", true));
				log.info("NotAuthorizedException for packet: " + packet.toString());
			} catch (PacketErrorTypeException e2) {
				log.info("Packet processing exception: " + e2);
			}
		}    // end of try-catch

		return false;
	}
}


//~ Formatted in Sun Code Convention


//~ Formatted by Jindent --- http://www.jindent.com
