/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/
package com.sun.fortress.interpreter.drivers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;

import xtc.parser.ParseError;
import xtc.parser.Result;
import xtc.parser.SemanticValue;

import com.sun.fortress.interpreter.reader.Lex;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes_util.Printer;
import com.sun.fortress.nodes_util.Unprinter;
import com.sun.fortress.parser.Fortress;
import com.sun.fortress.useful.StringEncodedAggregate;
import com.sun.fortress.useful.Useful;

import edu.rice.cs.plt.tuple.Option;

public class ASTIO {

    /**
     * @param p
     * @param fout
     * @throws IOException
     */
    public static void writeJavaAst(CompilationUnit p, BufferedWriter fout)
            throws IOException {
        (new Printer()).dump(p, fout, 0);
    }

    /**
     * Runs a command and captures its output and errors streams.
     *
     * @param command
     *            The command to run
     * @param output
     *            Output from the command is written here.
     * @param errors
     *            Errors from the command are written here.
     * @param exceptions
     *            If the execution of the command throws an exception, it is
     *            stored here.
     * @return true iff any errors were written.
     * @throws IOException
     */
    
    public static void writeJavaAst(CompilationUnit p, String s)
            throws IOException {
        BufferedWriter fout = Useful.utf8BufferedFileWriter(s);
        try { writeJavaAst(p, fout); }
        finally { fout.close(); }
    }

    /**
     * Convenience method for calling parseToJavaAst with a default BufferedReader.
     */
    public static Option<CompilationUnit> parseToJavaAst(APIName api_name, String reportedFileName) throws IOException {
        BufferedReader r = Useful.utf8BufferedFileReader(reportedFileName);
        try { return ASTIO.parseToJavaAst(api_name, reportedFileName, r); }
        
        finally { r.close(); }
    }

    /**
     * Returns a string encoding an api name and a file name, suitable for passing
     * through the Rats!-generated parser interface (that expects a string).
     * If api_name is null, then the empty string is used instead.
     * Any backslashes in the file name are converted to forward slashes.
     * 
     * @param api_name
     * @param file_name
     * @return
     */

//     public static String bundleParserArgs(APIName api_name, String file_name) {
//         String api_string = api_name == null ? "" : api_name.toString();
//         file_name = file_name.replace('\\', '/');
//         String s = StringEncodedAggregate.mapPairToString("file", file_name, "cuname", api_string, '\\').toString();
//         return s;
//     }
    
//     public static String getParserFile(String s) {
//         return StringEncodedAggregate.getFromEncodedMap(s, '\\', "file");
//     }
    
//     public static String getParserAPI(String s) {
//         return StringEncodedAggregate.getFromEncodedMap(s, '\\', "cuname");
//     }
    
//     public static String bundleParserArgs(APIName api_name, File f) throws IOException {
//         return bundleParserArgs(api_name, f.getCanonicalPath());
//     }

    public static Option<CompilationUnit> parseToJavaAst (APIName api_name, 
            String reportedFileName, BufferedReader in)
        throws IOException
    {
        File f = new File(reportedFileName);
        Fortress p = new Fortress(in, reportedFileName, (int)f.length());
        p.setExpectedName(api_name);
        Result r = p.pFile(0);
    
        if (r.hasValue()) {
            SemanticValue v = (SemanticValue) r;
            CompilationUnit n = (CompilationUnit) v.value;
            
            return Option.some(n);
        }
        else {
            ParseError err = (ParseError) r;
            if (-1 == err.index) {
                System.err.println("  Parse error");
            }
            else {
                System.err.println("  " + p.location(err.index) + ": "
                        + err.msg);
            }
            return Option.none();
        }
    }

   /**
     * @param reportedFileName
     * @param br
     * @throws IOException
     */
    public static Option<CompilationUnit>
        readJavaAst(String reportedFileName, BufferedReader br)
        throws IOException
    {
        Lex lex = new Lex(br);
        try {
            Unprinter up = new Unprinter(lex);
            lex.name();
            CompilationUnit p = (CompilationUnit) up.readNode(lex.name());
            if (p == null) { return Option.none(); }
            else { return Option.some(p); }
        }
        finally {
            if (!lex.atEOF())
                System.out.println("Parse of " + reportedFileName
                        + " ended EARLY at line = " + lex.line()
                        + ",  column = " + lex.column());
        }
    }

    public static Option<CompilationUnit> readJavaAst(String fileName)
            throws IOException {
        BufferedReader br = Useful.utf8BufferedFileReader(fileName);
        try { return readJavaAst(fileName, br); }
        finally { br.close(); }
    }

 
}
