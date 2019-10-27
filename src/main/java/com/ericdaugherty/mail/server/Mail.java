/******************************************************************************
 * $Workfile: Mail.java $
 * $Revision: 120 $
 * $Author: edaugherty $
 * $Date: 2003-10-15 14:06:23 -0500 (Wed, 15 Oct 2003) $
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

package com.ericdaugherty.mail.server;

//Java imports
import java.io.*;
//Log4j2 imports
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

//Local imports
import com.ericdaugherty.mail.server.configuration.ConfigurationManager;
import com.ericdaugherty.mail.server.services.general.ServiceListener;
import com.ericdaugherty.mail.server.services.smtp.SMTPSender;
import com.ericdaugherty.mail.server.services.smtp.SMTPProcessor;
import com.ericdaugherty.mail.server.services.pop3.Pop3Processor;

/**
 * This class is the entrypoint for the Mail Server application.  It creates
 * threads to listen for SMTP and POP3 connections.  It also handles the
 * configuration information and initialization of the User subsystem.
 *
 * @author Eric Daugherty
 */
public class Mail {

    //***************************************************************
    // Variables
    //***************************************************************

    //Threads

    private static ServiceListener popListener;
    private static ServiceListener smtpListener;
    private static SMTPSender smtpSender;
    private static ShutdownService shutdownService;

    /** The SMTP sender thread */
    private static Thread smtpSenderThread;

    /** The ShutdownService Thread.  Started when the JVM is shutdown. */
    private static Thread shutdownServiceThread;

    /** Logger Category for this class.  This variable is initialized once
     * the main logging system has been initialized */
    private static final Logger logger = LogManager.getLogger(Mail.class.getName());

    //***************************************************************
    // Public Interface
    //***************************************************************

    /**
     * Provides a 'safe' way for the application to shut down.  This
     * method is provided to enable compatability with the JavaService
     * NT Service wrapper class.  It defers the call to the shutdown method.
     *
     * @param args
     */
    public static void shutdown( String[] args ) {
        logger.debug( "NT Service requested application shutdown." );
        shutdown();
    }

    /**
     * Provides a 'safe' way for the application to shut down.  It will attempt
     * to stop the running threads.  If the threads to not stop within 60 seconds,
     * the application will force the threads to stop by calling System.exit();
     */
    public static void shutdown() {

        logger.warn( "Shutting down Mail Server.  Server will shut down in 60 seconds." );

        popListener.shutdown();
        smtpListener.shutdown();
        smtpSender.shutdown();

        try{
            smtpSenderThread.join(10000);
        }
        catch (InterruptedException ie)
        {
            logger.error("Was interrupted while waiting for thread to die");
        }

        logger.info("Thread gracefully terminated");
        smtpSenderThread = null;
    }

    //***************************************************************
    // Main Method

    /**
     * This method is the entrypoint to the system and is responsible
     * for the initial configuration of the application and the creation
     * of all 'service' threads.
     * @param args
     */
    public static void main( String[] args ) {

        // Perform the basic application startup.  If anything goes wrong here,
        // we need to abort the application.
        try {

            // Get the 'root' directory for the mail server.
            String directory = getConfigurationDirectory( args );

            // Initialize the Configuration Manager.
            ConfigurationManager configurationManager = ConfigurationManager.initialize( directory );

            //Start the threads.
            int port;
            int executeThreads = configurationManager.getExecuteThreadCount();

            //Start the Pop3 Thread.
            port = configurationManager.getPop3Port();
            if(logger.isDebugEnabled() ) logger.debug( "Starting POP3 Service on port: " + port );
            popListener = new ServiceListener( port, Pop3Processor.class, executeThreads );
            new Thread( popListener, "POP3" ).start();

            //Start SMTP Threads.
            port = configurationManager.getSmtpPort();
            if( logger.isDebugEnabled() ) logger.debug( "Starting SMTP Service on port: " + port );
            smtpListener = new ServiceListener( port, SMTPProcessor.class, executeThreads );
            new Thread( smtpListener, "SMTP" ).start();

            //Start the SMTPSender thread (This thread actually delivers the mail recieved
            //by the SMTP threads.
            smtpSender = new SMTPSender();
            smtpSenderThread = new Thread( smtpSender, "SMTPSender" );
            smtpSenderThread.start();

            //Initialize ShutdownService
            shutdownService = new ShutdownService();
            shutdownServiceThread = new Thread(shutdownService);
            Runtime.getRuntime().addShutdownHook( shutdownServiceThread );
        }
        catch( RuntimeException runtimeException ) {
            logger.error("The application failed to initialize." );
            logger.error( runtimeException.getMessage() );
            System.exit( 0 );
        }
    }

    //***************************************************************
    // Private Interface
    //***************************************************************

    /**
     * Parses the input parameter for the configuration directory, or defaults
     * to the local directory.
     *
     * @param args the commandline arguments.
     * @return the directory to use as the 'root'.
     */
    private static String getConfigurationDirectory( String[] args ) {

        String directory = "conf";
        File directoryFile;

        // First, check to see if the location was passed as a paramter.
        if (args.length == 1) {
            directory = args[0];
        }
        // Otherwise, use the default, which is 'mail.conf' in the current directory.
        else if( (directoryFile = new File( directory )).exists() ) {
            System.out.println( "Configuration Directory not specified.  Using \"" + directoryFile.getAbsolutePath() + "\"" );
        }
        // If no file was specified and the default does not exist, printing out a usage line.
        else {
            System.out.println("Usage:  java com.ericdaugherty.mail.server.Mail <configuration directory>");
            throw new RuntimeException( "Unable to load the configuration file." );
        }

        return directory;
    }
}
