/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.atmosphere.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Format the record to include the Thread that logged the record.
 * To change the default configuration for java.util.logging you will need to
 * add this in the command line parameters : -Djava.util.logging.config.file=myfile
 * <p/>
 * Here a sample of what you need to include in myfile
 * <p/>
 * #the default logger is this add you can replace it with LoggingFormatter
 * #java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter
 * java.util.logging.ConsoleHandler.formatter = com.sun.grizzly.util.LoggingFormatter
 * <p/>
 * refer to : https://grizzly.dev.java.net/issues/show_bug.cgi?id=291
 *
 * @author Sebastien Dionne
 */
public class LoggingFormatter extends Formatter {

    private static Logger log = Logger.getLogger(LoggingFormatter.class.getName());
    // took that from the JDK java.util.logging.SimpleFormatter
    // Line separator string.  This is the value of the line.separator
    // property at the moment that the SimpleFormatter was created.
    private static String lineSeparator = "\n";

    static {
        try {
            String separator = System.getProperty("line.separator");

            if (separator != null && separator.trim().length() > 0) {
                lineSeparator = separator;
            }
        } catch (SecurityException se) {
            // ignore the exception
        }

    }

    public LoggingFormatter() {
        super();
    }

    /**
     * Format the record to include the Thread that logged this record.
     * the format should be
     * [WorkerThreadImpl-1, Grizzly] 2008-10-08 18:49:59 [INFO] com.sun.grizzly.Controller:doSelect message
     *
     * @param record The record to be logged into the logger.
     * @return the record formated to be more human readable
     */
    public String format(LogRecord record) {

        // Create a StringBuffer to contain the formatted record
        StringBuffer sb = new StringBuffer();

        sb.append("[").append(Thread.currentThread().getName()).append("] ");

        // Get the date from the LogRecord and add it to the buffer
        Date date = new Date(record.getMillis());
        sb.append(date.toString()).append(" ");

        // Get the level name and add it to the buffer
        sb.append("[").append(record.getLevel().getLocalizedName()).append("] ");

        // Get Class name
        if (record.getSourceClassName() != null) {
            sb.append(record.getSourceClassName());
        } else {
            sb.append(record.getLoggerName());
        }
        // Get method name
        if (record.getSourceMethodName() != null) {
            sb.append(" ");
            sb.append(record.getSourceMethodName());
        }
        sb.append(":");

        // Get the formatted message (includes localization
        // and substitution of parameters) and add it to the buffer
        sb.append(formatMessage(record)).append(lineSeparator);

        //we log the stackTrace if it's a exception
        if (record.getThrown() != null) {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                record.getThrown().printStackTrace(pw);
                pw.close();
                sb.append(sw.toString());
            } catch (Exception ex) {
            }
        }
        sb.append(lineSeparator);

        return sb.toString();
    }

    /**
     * Example to test the com.sun.grizzly.util.LoggingFormatter
     * You need to include this parameter in the command line
     * -Djava.util.logging.config.file=myfile
     *
     * @param args main parameters
     */
    public static void main(String[] args) {

        log.info("Info Event");

        log.severe("Severe Event");

        // show the thread info in the logger.
        Thread t = new Thread(new Runnable() {

            public void run() {
                log.info("Info Event in Thread");
            }
        }, "Thread into main");
        t.start();

        log.log(Level.SEVERE, "exception", new Exception());


    }
}