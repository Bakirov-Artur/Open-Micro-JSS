/******************************************************************************
 * $Workfile: DeliveryService.java $
 * $Revision: 164 $
 * $Author: edaugherty $
 * $Date: 2004-07-14 14:19:34 -0500 (Wed, 14 Jul 2004) $
 *
 ******************************************************************************
 * This program is a 100% Java Email Server.
 ******************************************************************************
 * Copyright (C) 2001, Eric Daugherty
 * All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 ******************************************************************************
 * For current versions and more information, please visit:
 * http://www.ericdaugherty.com/java/mail
 * 
 * or contact the author at:
 * java@ericdaugherty.com
 * 
 ******************************************************************************
 * This program is based on the CSRMail project written by Calvin Smith.
 * http://crsemail.sourceforge.net/
 *****************************************************************************/

package com.ericdaugherty.mail.server.services.general;

//Java imports
import java.util.*;

//Log4j2 imports
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

//Local imports
import com.ericdaugherty.mail.server.info.EmailAddress;
import com.ericdaugherty.mail.server.configuration.ConfigurationParameterContants;
import com.ericdaugherty.mail.server.configuration.ConfigurationManager;

/**
 * Handles the evalution of general mail delivery rules, including SMTP Relaying.
 *
 */
public class DeliveryService implements ConfigurationParameterContants {

    //***************************************************************
    // Variables
    //***************************************************************

    /** Logger Category for this class. */
    private static final Logger logger = LogManager.getLogger(DeliveryService.class.getName());

    /** Singleton Instance */
    private static DeliveryService instance = null;

    private final ConfigurationManager configurationManager;

    /** The IP Addresses that have logged into the POP3 server recently */
    private final Hashtable authenticatedIps;

    /** The mailboxes that are currently locked */
    private final Hashtable lockedMailboxes;

    //***************************************************************
    // Public Interface
    //***************************************************************

    //***************************************************************
    // Constructor

    /**
     * Load the paramters from the Mail configuration.
     */
    protected DeliveryService() {

        configurationManager = ConfigurationManager.getInstance();

        //Initialize the Hashtable for tracking authenticated ip addresses.
        authenticatedIps = new Hashtable();
        //Initialize the Hashtable for tracking locked mailboxes
        lockedMailboxes = new Hashtable();
    }

    //***************************************************************
    // Public Interface

    /**
     * accessory for the singleton instance for this class.
     * @return 
     */
    public static synchronized DeliveryService getDeliveryService(){
        if ( instance == null ) {
            instance = new DeliveryService();
        }
        return instance;
    }


    /**
     * Determines if the domain for the specified email address is hosted
     * locally in this mail server.
     * @param address
     * @return 
     */
    public boolean isLocalAddress( EmailAddress address ) {

        return configurationManager.isLocalDomain( address.getDomain() );
    }

    /**
     * Checks an address to see if we should accept it for delivery.
     * @return 
     */
    public boolean acceptAddress( EmailAddress address, String clientIp, EmailAddress clientFromAddress ) {

        // Check to see if the email should be address should be accepted
        // for delivery.
        boolean isValid = false;

        // Set isValid to true if one of the rules matches.
        isValid = isLocalAddress( address ) || // Accept all local email.
            ( configurationManager.isEnablePOPBeforeSMTP() && isAuthenticated( clientIp ) ) ||
            ( isRelayApproved( clientIp, configurationManager.getRelayApprovedIpAddresses() ) ||
            ( isRelayApprovedForEmail( clientFromAddress, configurationManager.getRelayApprovedEmailAddresses()) ) );

        return isValid;
    }

    /**
     * This method should be called whenever a client authenticates themselves.
     * @param clientIp
     */
    public void ipAuthenticated( String clientIp ) {
        if( logger.isDebugEnabled() ) logger.debug( "Adding authenticated IP address: {}", clientIp );
        authenticatedIps.put( clientIp, new Date() );
    }

    /**
     * This method locks a mailbox so that two clients can not access the same mailbox
     * at the same time.
     * @param address
     */
    public void lockMailbox( EmailAddress address ) {
        lockedMailboxes.put( address.getAddress(), "" );
    }

    /**
     * Checks to see if a user currently has the specified mailbox locked.
     * @param address
     * @return 
     */
    public boolean isMailboxLocked( EmailAddress address ) {
        if( logger.isDebugEnabled() ) logger.debug( "Locking Mailbox: {}", address.getAddress() );
        return lockedMailboxes.containsKey( address.getAddress() );
    }

    /**
     * Unlocks an mailbox.
     * @param address
     */
    public void unlockMailbox( EmailAddress address ) {
        if( logger.isDebugEnabled() ) logger.debug( "Unlocking Mailbox: {}", address.getAddress() );
        lockedMailboxes.remove( address.getAddress() );
    }
    
    //***************************************************************
    // Private Interface
    //***************************************************************
    
    /**
     * Checks the current state to determine if a user from this
     * IP address has authenticated with the POP3 server with the
     * timeout length.
     */
    private boolean isAuthenticated( String clientIp ) {

        boolean retval = false;
        
        //Do a quick check to see if this ip is even registered
        //in the HashTable.
        if( authenticatedIps.containsKey( clientIp ) ) {
            Date authenticationDate = (Date) authenticatedIps.get( clientIp );
            
            //Calculate the current time and the time that the login will timeout.
            long currentTime = System.currentTimeMillis();
            long timeoutTime = authenticationDate.getTime() + configurationManager.getAuthenticationTimeoutMilliseconds();
            
            //If the timeout time is in the future, the ip is still authenticated.
            if( timeoutTime > currentTime ) {
                retval = true;
            }
            else {
                //If the IP address has timed out, remove it from the hashtable.
                authenticatedIps.remove( clientIp );
            }
        }
        return retval;
    }

    /**
     * Returns true if the client IP address matches an IP address in the
     * approvedAddresses array.
     *
     * @param clientIp The IP address to test.
     * @param approvedAddresses The approved list.
     * @return true if the address is approved.
     */
    private boolean isRelayApproved( String clientIp, String[] approvedAddresses ) {

        String approvedAddress;
        for (String approvedAddresse : approvedAddresses) {
            approvedAddress = approvedAddresse;
            // Check for an exact match.
            if( clientIp.equals( approvedAddress ) ) {
                return true;
            }
            // Check for a partial match
            else {
                int wildcardIndex = approvedAddress.indexOf( "*" );
                if( wildcardIndex != -1 ) {
                    boolean isMatch = true;
                    StringTokenizer clientIpTokenizer = new StringTokenizer( clientIp, "." );
                    StringTokenizer approvedAddressTokenizer = new StringTokenizer( approvedAddress,  "." );
                    String clientIpToken;
                    String approvedAddressToken;
                    while( clientIpTokenizer.hasMoreTokens() )
                    {
                        try {
                            clientIpToken = clientIpTokenizer.nextToken().trim();
                            approvedAddressToken = approvedAddressTokenizer.nextToken().trim();
                            if( !clientIpToken.equals( approvedAddressToken) && !approvedAddressToken.equals( "*" ) ) {
                                isMatch = false;
                                break;
                            }
                        }
                        catch (NoSuchElementException noSuchElementException) {
                            logger.warn( "Invalid ApprovedAddress found: {}.  Skipping.", approvedAddress);
                            isMatch = false;
                            break;
                        }
                    }
                    // Return true if you had a match.
                    if (isMatch) return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the client email address matches an email address in the
     * approvedEmailAddresses array.
     *
     * @param clientFromEmail The email address to test.
     * @param approvedEmailAddresses The approved list.
     * @return true if the email address is approved.
     */
    private boolean isRelayApprovedForEmail( EmailAddress clientFromEmail, String[] approvedEmailAddresses ) {

        String approvedEmailAddress;
        for (String approvedEmailAddresse : approvedEmailAddresses) {
            approvedEmailAddress = approvedEmailAddresse.trim();
            // Check for an exact match (case insensitive).
            if( clientFromEmail.getAddress().equalsIgnoreCase( approvedEmailAddress ) ) {
                return true;
            }
            else if (approvedEmailAddress.startsWith("@")) {
                // Check for a domain
                String domain=approvedEmailAddress.substring(1);
                if (clientFromEmail.getDomain().endsWith(domain)) {
                    return(true);
                }
            }
        }
        return false;
    }
}
//EOF