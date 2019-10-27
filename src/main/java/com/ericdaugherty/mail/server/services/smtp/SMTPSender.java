/******************************************************************************
 * $Workfile: SMTPSender.java $
 * $Revision: 160 $
 * $Author: edaugherty $
 * $Date: 2003-12-23 20:29:38 -0600 (Tue, 23 Dec 2003) $
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

package com.ericdaugherty.mail.server.services.smtp;

//Java imports
import java.io.*;
import java.util.Date;
import java.util.List;

//Log imports
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

//Local imports
import com.ericdaugherty.mail.server.info.User;
import com.ericdaugherty.mail.server.info.EmailAddress;
import com.ericdaugherty.mail.server.errors.NotFoundException;
import com.ericdaugherty.mail.server.services.general.DeliveryService;
import com.ericdaugherty.mail.server.configuration.ConfigurationManager;
import java.util.ArrayList;

/**
 * This class (thread) is responsible for checking the disk for unsent message and
 * delivering them to the proper local address or remote smtp server.
 * <p>
 * There should be only one instance of this thread running in the system at
 * any one time.
 */
public class SMTPSender implements Runnable {

    //***************************************************************
    // Variables
    //***************************************************************

    /** Logger */
    private static final Logger logger = LogManager.getLogger(SMTPSender.class.getName());

    /** The ConfigurationManager */
    private static final ConfigurationManager configurationManager = ConfigurationManager.getInstance();

    private boolean running = true;

    //***************************************************************
    // Public Interface
    //***************************************************************

    /**
     * The entrypoint for this thread.  This method handles the lifecycle
     * of this thread.
     */
    @Override
    public void run() {

        while( running ) {

            try {

                logger.debug( "Checking for SMTP messages to deliver" );

                File smtpDirectory = new File( configurationManager.getMailDirectory() + File.separator + "smtp" );

                if( smtpDirectory.exists() && smtpDirectory.isDirectory() ) {

                    File[] files = smtpDirectory.listFiles();
                    int numFiles = files.length;

                    for( int index = 0; index < numFiles; index++ ) {
                        try {
                            deliver( SMTPMessage.load( files[index].getAbsolutePath() ) );
                        }
                        catch( Throwable throwable ) {
                            logger.error( "An error occured attempting to deliver an SMTP Message: " + throwable, throwable );
                            //Do nothing else, contine on to the next message.
                        }
                    }
                }

                //Rest the specified sleep time.  If it is greater than 10 seconds
                //Wake up every 10 seconds to check to see if the thread is shutting
                //down.
                long sleepTime = configurationManager.getDeliveryIntervealMilliseconds();
                if( configurationManager.getDeliveryIntervealMilliseconds() < 10000 ) {
                    Thread.sleep( sleepTime );
                }
                else {
                    long totalSleepTime = sleepTime;
                    while( totalSleepTime > 0 && running ) {
                        if( totalSleepTime > 10000 ) {
                            totalSleepTime -= 10000;
                            Thread.sleep( 10000);
                        }
                        else {
                            Thread.sleep( totalSleepTime );
                            totalSleepTime = 0;
                        }
                    }
                }
            }
            catch( InterruptedException ie ) {
                logger.error( "Sleeping Thread was interrupted." );
            }
            catch( Throwable throwable )
            {
                logger.error( "An error occured attempting to deliver an SMTP Message: " + throwable, throwable );
            }
        }
        logger.warn( "SMTPSender shut down gracefully.");
    }

    /**
     * Notifies this thread to stop processing and exit.
     */
    public void shutdown() {
        logger.warn( "Attempting to shut down SMTPSender." );
        running = false;
    }

    //***************************************************************
    // Private Interface
    //***************************************************************

    /**
     * This method takes a SMTPMessage and attempts to deliver it.  This
     * method assumes that all the addresses have been validated before,
     * and does not perform any delivery rules.
     */
    private void deliver( SMTPMessage message ) {
        List toAddresses = message.getToAddresses();
        List failedAddress = new ArrayList();
        EmailAddress address = null;

        // If the next scheduled delivery attempt is still in the future, skip.
        if( message.getScheduledDelivery().getTime() > System.currentTimeMillis() )
        {
            if( logger.isDebugEnabled() ) logger.debug( "Skipping delivery of message " + message.getMessageLocation().getName() + " because the scheduled delivery time is still in the future: " + message.getScheduledDelivery() );
            return;
        }
        
        int countRecipient = toAddresses.size();
        for( int index = 0; index < countRecipient; index++ ) {
            try {
                address = (EmailAddress) toAddresses.get( index );
                if( logger.isDebugEnabled()) { logger.debug( "Attempting to deliver message from: " + message.getFromAddress().getAddress() + " to: " + address ); }

                DeliveryService deliveryService = DeliveryService.getDeliveryService();

                try {
                    if( deliveryService.isLocalAddress( address ) ) {
                        deliverLocalMessage( address, message );
                    }
                    else {
                        deliverRemoteMessage( address, message );
                    }
                }
                catch (NotFoundException e) {
                    logger.info( "Delivery attempted to unknown user: " + address.getAddress() );
                    //The addressee does not exist.  Notify the sender of the error.
                    bounceMessage( address, message );
                }

                if( logger.isInfoEnabled() ) { logger.info( "Delivery complete for message " + message.getMessageLocation().getName() + " to: " + address ); }
            }
            catch( Throwable throwable ) {
                logger.error( "Delivery failed for message from: " + message.getFromAddress().getAddress() + " to: " + address + " - " + throwable, throwable );
                failedAddress.add( toAddresses.get( index ) );
            }
        }
        // Update the message with any changes.
        if( !failedAddress.isEmpty()) {
            
            message.setToAddresses( failedAddress );
            int deliveryAttempts = message.getDeliveryAttempts();
            
            
            // If the message is a bounced email, just give up and move it to the failed directory.
            if(message.getFromAddress().getUsername().equalsIgnoreCase("MAILER_DAEMON"))
            {
                try {
                    logger.info( "Delivery of message from MAILER_DAEMON failed, moving to failed folder." );
                    message.moveToFailedFolder();
                }
                catch (Exception e) {
                    logger.error( "Unable to move failed message to 'failed' folder." );
                }
            }
            // If we have not passed the maximum delivery count, calculate the
            // next delivery time and save the message.
            else if(  deliveryAttempts < configurationManager.getDeliveryAttemptThreshold() )
            {
                message.setDeliveryAttempts( deliveryAttempts + 1 );

                // Reschedule later, 1 min, 2 min, 4 min, 8 min, ... 2^n
                // Cap delivery interval at 2^10 minutes. (about 17 hours)
                if( deliveryAttempts > 10 )
                {
                    deliveryAttempts = 10;
                }
                long offset = (long)Math.pow( 2, deliveryAttempts);
                Date schedTime = new Date(System.currentTimeMillis() + offset*60*1000);
                message.setScheduledDelivery( schedTime );

                try {
                    message.save();
                }
                catch( Exception exception ) {
                    logger.error( "Error updating spooled message for next delivery.  Message may be re-delivered.", exception );
                }
            }
            // All delivery attempts failed, bounce message.
            else
            {
                // Send a bounce message to all failed addresses.
                for( int index = 0; index < failedAddress.size(); index++ ) {
                    try {
                        EmailAddress bounce_address = (EmailAddress)( failedAddress.get(index) );
                        bounceMessage(bounce_address, message);
                    }
                    catch(Exception e) {
                        logger.error( "Problem bouncing message. " + message.getMessageLocation().getName() );
                    }
                }

                // Remove the original message.
                if( !message.getMessageLocation().delete() )
                {
                    logger.error( "Error removed SMTP message after bounce! This message may be re-bounced. " + message.getMessageLocation().getName() );
                }
            }
        }
        // If all addresses were successful, remove the message from the spool
        else {
            // Log an error if the delete fails.  This will cause the message to get
            // delivered again, but it is too late to roll back the delivery.
            if( !message.getMessageLocation().delete() )
            {
                logger.error( "Error removed SMTP message after delivery!  This message may be redelivered. " + message.getMessageLocation().getName() );
            }
        }
    }

    /**
     * This method takes a local SMTPMessage and attempts to deliver it.
     */
    private void deliverLocalMessage( EmailAddress address, SMTPMessage message )
        throws NotFoundException {

        if( logger.isDebugEnabled() ) { logger.debug( "Delivering Message to local user: " + address.getAddress() ); }
        //Load the user.  If the user doesn't exist, a not found exception will
        //be thrown and the deliver() message will deal with the notification.
        User user = configurationManager.getUser( address );
        if( user == null )
        {
            logger.debug( "User not found, checking for default delivery options" );
            //Check to see if a default delivery mailbox exists, and if so, deliver it.
            //Otherwise, just throw the NotFoundException to bounce the email.
            if( configurationManager.isDefaultUserEnabled() ) {
                EmailAddress defaultAddress = configurationManager.getDefaultUser();
                //If this throws a NotFoundException, go ahead and let it bounce.
                user = configurationManager.getUser( defaultAddress );
                if( user == null ) throw new NotFoundException();
                if( logger.isDebugEnabled() ) { logger.info( "Delivering message addressed to: " + address + " to default user: " + defaultAddress ); }
            }
            else {
                throw new NotFoundException( "User does not exist and no default delivery options found." );
            }
        }

        //The file to write to.
        File messageFile = null;
        //The output stream to write the message to.
        BufferedWriter out = null;

        try {

            //Get the directory and create a new file.
            messageFile = File.createTempFile("pop", ".jmsg", user.getUserDirectory() );

            if( logger.isDebugEnabled() ) { logger.debug( "Delivering to: " + messageFile.getAbsolutePath() ); }

            //Open the output stream.
            out = new BufferedWriter( new FileWriter( messageFile ) );

            //Get the data to write.
            List dataLines = message.getDataLines();
            int numDataLines = dataLines.size();

            //Write the X-DeliveredTo: header
            out.write( "X-DeliveredTo: " + address.getAddress() );
            out.write( "\r\n" );

            //Write the data.
            for( int index = 0; index < numDataLines; index++ ) {
                out.write( (String) dataLines.get( index ) );
                out.write( "\r\n" );
            }
        }
        catch( IOException ioe ) {
            logger.error( "Error performing local delivery.", ioe );
            if( messageFile != null ) {
                //The message was not fully written, so delete it.
                messageFile.delete();
            }
        }
        finally {
            if( out != null ) {
                try {
                    //Make sure we close up the output stream.
                    out.close();
                }
                catch( IOException ioe ) {
                    logger.error( "Error closing output Stream.", ioe );
                }
            }
        }
    }

    /**
     * Handles delivery of messages to addresses not handled by this server.
     */
    private void deliverRemoteMessage( EmailAddress address, SMTPMessage message ) throws NotFoundException {

        if( logger.isDebugEnabled() ) { logger.debug( "Delivering Message to remote user: " + address ); }

        //Delegate this request to the SMTPRemoteSender class.
        new SMTPRemoteSender().sendMessage( address, message );
    }

    private void bounceMessage( EmailAddress address, SMTPMessage message ) {

        if( logger.isInfoEnabled() ) { logger.info( "Bouncing Messsage from " + message.getFromAddress().getAddress() + " to " + address.getAddress() ); }

        SMTPMessage bounceMessage = new SMTPMessage();

        //Set the from address as mailserver@ the first (default) local domain.
        EmailAddress fromAddress = new EmailAddress( "MAILER_DAEMON", configurationManager.getLocalDomains()[0] );

        bounceMessage.setFromAddress( fromAddress );
        bounceMessage.addToAddress( message.getFromAddress() );
        bounceMessage.addDataLine( "From: Mail Delivery Subsystem <MAILER-DAEMON@" + configurationManager.getLocalDomains()[0] + ">" );
        bounceMessage.addDataLine( "To: " + message.getFromAddress().getAddress() );
        bounceMessage.addDataLine( "Subject: Message Delivery Error." );
        bounceMessage.addDataLine( "Date: " + new Date().toString() ); //TODO: Improve date handling.
        bounceMessage.addDataLine( "" );
        bounceMessage.addDataLine( "Error delivering message to: " + address.getAddress() );
        bounceMessage.addDataLine( "This message will not be delivered." );
        bounceMessage.addDataLine( "" );
        bounceMessage.addDataLine( "------------------" );

        List dataLines = message.getDataLines();

        int numLines = dataLines.size();

        for( int index = 0; index <  numLines; index++ ) {
            bounceMessage.addDataLine( (String) dataLines.get( index ) );
        }
        bounceMessage.addDataLine( "" );

        //Save this message so it will be delivered.
        try {
            bounceMessage.save();
        }
        catch (Exception e) {
            logger.error( "Error storing outgoing 'bounce' email message");
            throw new RuntimeException();
        }
    }
}
//EOF