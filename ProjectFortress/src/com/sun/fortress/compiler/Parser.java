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

package com.sun.fortress.compiler;

import java.io.File;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.FileNotFoundException;
import java.io.IOException;

import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.io.IOUtil;

import xtc.parser.ParserBase;
import xtc.parser.SemanticValue;
import xtc.parser.ParseError;
//import xtc.parser.Result; // Not imported to prevent name clash.
import com.sun.fortress.parser.Fortress; // Shadows Fortress in this package
import com.sun.fortress.parser.preparser.PreFortress;
import com.sun.fortress.parser_util.SyntaxChecker;

import com.sun.fortress.useful.Files;
import com.sun.fortress.useful.Useful;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.exceptions.ParserError;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.repository.ProjectProperties;


/**
 * Methods for parsing files using base Fortress syntax.
 * For a parser that respects abstractions, use
 * com.sun.fortress.syntax_abstractions.parser.FortressParser.
 */
public class Parser {

    public static class Result extends StaticPhaseResult {
        private final Iterable<Api> _apis;
        private final Iterable<Component> _components;
        private long _lastModified;

        public Result(CompilationUnit cu, long lastModified) {
            if (cu instanceof Api) {
                _apis = IterUtil.singleton((Api)cu);
                _components = IterUtil.empty();
            } else if (cu instanceof Component) {
                _apis = IterUtil.empty();
                _components = IterUtil.singleton((Component)cu);
            } else {
                throw new RuntimeException("Unexpected parse result: " + cu);
            }
            _lastModified = lastModified;
        }

        public Result(StaticError error) {
            super(IterUtil.singleton(error));
            _apis = IterUtil.empty();
            _components = IterUtil.empty();
        }

        public Result(Iterable<? extends StaticError> errors) {
            super(errors);
            _apis = IterUtil.empty();
            _components = IterUtil.empty();
        }

        public Iterable<Api> apis() { return _apis; }
        public Iterable<Component> components() { return _components; }
        public long lastModified() { return _lastModified; }
    }

    /**
     * Convert to a filename that is canonical for each (logical) file, preventing
     * reparsing the same file.
     * FIXME: DEAD CODE
     */
    private static File canonicalRepresentation(File f) {
        // treat the same absolute path as the same file; different absolute path but
        // the same *canonical* path (a symlink, for example) is treated as two
        // different files; if absolute file can't be determined, assume they are
        // distinct.
        return IOUtil.canonicalCase(IOUtil.attemptAbsoluteFile(f));
    }


    /**
     * Parses a file as a compilation unit. Validates the parse by calling
     * checkResultCU (see also description of exceptions there).
     * Converts checked exceptions like IOException and FileNotFoundException
     * to StaticError with appropriate error message.
     */
    public static CompilationUnit parseFileConvertExn(File file) {
        try {
            preparseFileConvertExn(file);
            BufferedReader in = Useful.utf8BufferedFileReader(file);
            String filename = file.getCanonicalPath();
            try {
                Fortress parser = new Fortress(in, filename);
                xtc.parser.Result parseResult = parser.pFile(0);
                return checkResultCU(parseResult, parser, filename);
            } finally {
                Files.rm( filename + ".parserError.log" );
                in.close();
            }
        } catch (FileNotFoundException fnfe) {
            throw convertExn(fnfe, file);
        } catch (IOException ioe) {
            throw convertExn(ioe, file);
        }
    }

    /**
     * Parses a string as a compilation unit. See parseFile above.
     */
    public static CompilationUnit parseString(APIName api_name, String buffer) throws IOException {
        // Also throws StaticError, ParserError
        BufferedReader in = Useful.bufferedStringReader(buffer);
        try {
            Fortress parser = new Fortress(in, api_name.getText());
            xtc.parser.Result parseResult = parser.pFile(0);
            return checkResultCU(parseResult, parser, api_name.getText());
        } finally {
            in.close();
        }
    }

    /**
     * Checks that a xtc.parser.Result is contains a CompilationUnit,
     * and checks the filename for the appropriate suffix.
     * Throws a ParserError (note, subtype of StaticError) if the parse fails.
     * Throws a StaticError if the filename has the wrong suffix.
     */
    public static CompilationUnit checkResultCU(xtc.parser.Result parseResult,
                                                ParserBase parser,
                                                String filename) throws IOException {
        String parserLogFile;
        boolean isParse = false;
        if ( parser instanceof Fortress ) {
            parserLogFile = filename + ".parserError.log";
            isParse = true;
        } else if ( parser instanceof PreFortress ) {
            parserLogFile = filename + ".preparserError.log";
        } else {
            parserLogFile = filename + ".macroError.log";
        }
        File parserLog = new File( parserLogFile );

        if (parseResult.hasValue()) {
            CompilationUnit cu = (CompilationUnit)((SemanticValue) parseResult).value;
            if ( isParse ) {
                String syntaxLogFile = filename + ".syntaxError.log";
                cu.accept( new SyntaxChecker( Useful.filenameToBufferedWriter( syntaxLogFile ) ) );
                File syntaxLog = new File( syntaxLogFile );
                if ( parserLog.length() + syntaxLog.length() != 0 ) {
                    String messages = "";
                    BufferedReader reader = Useful.filenameToBufferedReader( parserLogFile );
                    String line = reader.readLine();
                    while ( line != null ) {
                        messages += (line + "\n");
                        line = reader.readLine();
                    }
                    reader = Useful.filenameToBufferedReader( syntaxLogFile );
                    line = reader.readLine();
                    while ( line != null ) {
                        messages += (line + "\n");
                        line = reader.readLine();
                    }
                    Files.rm( parserLogFile );
                    Files.rm( syntaxLogFile );
                    if ( messages.endsWith("\n") )
                        messages = messages.substring(0, messages.length()-1);
                    throw StaticError.make(messages);
                } else {
                    Files.rm( parserLogFile );
                    Files.rm( syntaxLogFile );
                }
            }
            else {
                if ( parserLog.length() != 0 ) {
                    String messages = "";
                    BufferedReader reader = Useful.filenameToBufferedReader( parserLogFile );
                    String line = reader.readLine();
                    while ( line != null ) {
                        messages += (line + "\n");
                        line = reader.readLine();
                    }
                    Files.rm( parserLogFile );
                    if ( messages.endsWith("\n") )
                        messages = messages.substring(0, messages.length()-1);
                    throw StaticError.make(messages);
                } else {
                    Files.rm( parserLogFile );
                }
            }

            if (cu instanceof Api) {
                if (filename.endsWith(ProjectProperties.API_SOURCE_SUFFIX)) {
                    return (Api)cu;
                } else {
                    throw StaticError.make("Api files must have suffix "
                                           + ProjectProperties.API_SOURCE_SUFFIX,
                                           (Api)cu);
                }
            } else if (cu instanceof Component) {
                if (filename.endsWith(ProjectProperties.COMP_SOURCE_SUFFIX)) {
                    return (Component)cu;
                } else {
                    throw StaticError.make("Component files must have suffix "
                                           + ProjectProperties.COMP_SOURCE_SUFFIX,
                                           (Component)cu);
                }
            } else {
                throw new RuntimeException("Unexpected parse result: " + cu);
            }
        } else {
            if ( parserLog.length() != 0 ) {
                System.err.println("Syntax error(s):");
                BufferedReader reader = Useful.filenameToBufferedReader( parserLogFile );
                String line = reader.readLine();
                while ( line != null ) {
                    System.err.println( line );
                    line = reader.readLine();
                }
                Files.rm( parserLogFile );
                throw new ParserError(new xtc.parser.ParseError("", 0), parser);
            } else {
                Files.rm( parserLogFile );
            }
            throw new ParserError((ParseError) parseResult, parser);
        }
    }

    // Pre-parser

    /**
     * Preparses a file as a compilation unit. Validates the parse by calling
     * checkResultCU (see also description of exceptions there).
     * Converts checked exceptions like IOException and FileNotFoundException
     * to StaticError with appropriate error message.
     */
    public static CompilationUnit preparseFileConvertExn(File file) {
        try {
            BufferedReader in = Useful.utf8BufferedFileReader(file);
            String filename = file.getCanonicalPath();
            try {
                PreFortress parser = new PreFortress(in, filename);
                xtc.parser.Result parseResult = parser.pFile(0);
                return checkResultCU(parseResult, parser, filename);
            } finally {
                Files.rm( filename + ".preparserError.log" );
                in.close();
            }
        } catch (FileNotFoundException fnfe) {
            throw convertExn(fnfe, file);
        } catch (IOException ioe) {
            throw convertExn(ioe, file);
        }
    }

    private static StaticError convertExn(IOException ioe, File f) {
        String desc = "Unable to read file";
        if (ioe.getMessage() != null) { desc += " (" + ioe.getMessage() + ")"; }
        return StaticError.make(desc, f.toString());
    }

    private static StaticError convertExn(FileNotFoundException fnfe, File f) {
        return StaticError.make("Cannot find file " + f.getName(), f.toString());
    }

}
