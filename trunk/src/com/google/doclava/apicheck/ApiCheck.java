/*
 * Copyright (C) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.doclava.apicheck;

import com.google.doclava.AnnotationInstanceInfo;
import com.google.doclava.ClassInfo;
import com.google.doclava.Errors;
import com.google.doclava.SourcePositionInfo;
import com.google.doclava.TypeInfo;

import com.google.doclava.FieldInfo;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

import sun.awt.motif.MComponentPeer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Stack;

public class ApiCheck {
  // parse out and consume the -whatever command line flags
  private static ArrayList<String[]> parseFlags(ArrayList<String> allArgs) {
    ArrayList<String[]> ret = new ArrayList<String[]>();

    int i;
    for (i = 0; i < allArgs.size(); i++) {
      // flags with one value attached
      String flag = allArgs.get(i);
      if (flag.equals("-error") || flag.equals("-warning") || flag.equals("-hide")) {
        String[] arg = new String[2];
        arg[0] = flag;
        arg[1] = allArgs.get(++i);
        ret.add(arg);
      } else {
        // we've consumed all of the -whatever args, so we're done
        break;
      }
    }

    // i now points to the first non-flag arg; strip what came before
    for (; i > 0; i--) {
      allArgs.remove(0);
    }
    return ret;
  }

  public static void main(String[] originalArgs) {
    // translate to an ArrayList<String> for munging
    ArrayList<String> args = new ArrayList<String>(originalArgs.length);
    for (String a : originalArgs) {
      args.add(a);
    }

    ArrayList<String[]> flags = ApiCheck.parseFlags(args);
    for (String[] a : flags) {
      if (a[0].equals("-error") || a[0].equals("-warning") || a[0].equals("-hide")) {
        try {
          int level = -1;
          if (a[0].equals("-error")) {
            level = Errors.ERROR;
          } else if (a[0].equals("-warning")) {
            level = Errors.WARNING;
          } else if (a[0].equals("-hide")) {
            level = Errors.HIDDEN;
          }
          Errors.setErrorLevel(Integer.parseInt(a[1]), level);
        } catch (NumberFormatException e) {
          System.err.println("Bad argument: " + a[0] + " " + a[1]);
          System.exit(2);
        }
      }
    }

    ApiCheck acheck = new ApiCheck();
    ApiInfo oldApi;
    ApiInfo newApi;
    
    try {
      oldApi = acheck.parseApi(args.get(0));
      newApi = acheck.parseApi(args.get(1));
    } catch (ApiParseException e) {
      e.printStackTrace();
      System.err.println("Error parsing API");
      System.exit(1);
      return;
    }

    // only run the consistency check if we haven't had XML parse errors
    if (!Errors.hadError) {
      oldApi.isConsistent(newApi);
    }

    Errors.printErrors();
    System.exit(Errors.hadError ? 1 : 0);
  }

  public ApiInfo parseApi(String xmlFile) throws ApiParseException {
    FileInputStream fileStream = null;
    try {
      fileStream = new FileInputStream(xmlFile);
      return parseApi(fileStream);
    } catch (IOException e) {
      throw new ApiParseException("Could not open file for parsing: " + xmlFile, e);
    } finally {
      if (fileStream != null) {
        try {
          fileStream.close();
        } catch (IOException ignored) {}
      }
    }
  }
  
  public ApiInfo parseApi(URL xmlURL) throws ApiParseException {
    InputStream xmlStream = null;
    try {
      xmlStream = xmlURL.openStream();
      return parseApi(xmlStream);
    } catch (IOException e) {
      throw new ApiParseException("Could not open stream for parsing: " + xmlURL,e);
    } finally {
      if (xmlStream != null) {
        try {
          xmlStream.close();
        } catch (IOException ignored) {}
      }
    }
  }
  
  public ApiInfo parseApi(InputStream xmlStream) throws ApiParseException {
    try {
      XMLReader xmlreader = XMLReaderFactory.createXMLReader();
      MakeHandler handler = new MakeHandler();
      xmlreader.setContentHandler(handler);
      xmlreader.setErrorHandler(handler);
      xmlreader.parse(new InputSource(xmlStream));
      ApiInfo apiInfo = handler.getApi();
      apiInfo.resolveSuperclasses();
      apiInfo.resolveInterfaces();
      return apiInfo;
    } catch (Exception e) {
      throw new ApiParseException("Error parsing API", e);
    }
  }

  private static class MakeHandler extends DefaultHandler {

    private ApiInfo mApi;
    private PackageInfo mCurrentPackage;
    private ClassInfo mCurrentClass;
    private AbstractMethodInfo mCurrentMethod;
    private Stack<ClassInfo> mClassScope = new Stack<ClassInfo>();


    public MakeHandler() {
      super();
      mApi = new ApiInfo();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      if (qName.equals("package")) {
        mCurrentPackage =
            new PackageInfo(attributes.getValue("name"), SourcePositionInfo.fromXml(attributes
                .getValue("source")));
      } else if (qName.equals("class") || qName.equals("interface")) {
        // push the old outer scope for later recovery, then set
        // up the new current class object
        mClassScope.push(mCurrentClass);
        mCurrentClass =
            new ClassInfo(attributes.getValue("name"), mCurrentPackage, attributes
                .getValue("extends"), qName.equals("interface"), Boolean.valueOf(attributes
                .getValue("abstract")), Boolean.valueOf(attributes.getValue("static")), Boolean
                .valueOf(attributes.getValue("final")), attributes.getValue("deprecated"),
                attributes.getValue("visibility"), SourcePositionInfo.fromXml(attributes
                    .getValue("source")), mCurrentClass);
      } else if (qName.equals("method")) {
        mCurrentMethod =
            new MethodInfo(attributes.getValue("name"), attributes.getValue("return"), Boolean
                .valueOf(attributes.getValue("abstract")), Boolean.valueOf(attributes
                .getValue("native")), Boolean.valueOf(attributes.getValue("synchronized")), Boolean
                .valueOf(attributes.getValue("static")), Boolean.valueOf(attributes
                .getValue("final")), attributes.getValue("deprecated"), attributes
                .getValue("visibility"), SourcePositionInfo.fromXml(attributes.getValue("source")),
                mCurrentClass);
      } else if (qName.equals("constructor")) {
        mCurrentMethod =
            new ConstructorInfo(attributes.getValue("name"), attributes.getValue("type"), Boolean
                .valueOf(attributes.getValue("static")), Boolean.valueOf(attributes
                .getValue("final")), attributes.getValue("deprecated"), attributes
                .getValue("visibility"), SourcePositionInfo.fromXml(attributes.getValue("source")),
                mCurrentClass);
      } else if (qName.equals("field")) {
        String visibility = attributes.getValue("visibility");
        boolean isPublic = visibility.equals("public");
        boolean isProtected = visibility.equals("protected");
        boolean isPrivate = visibility.equals("private");
        boolean isPackagePrivate = visibility.equals("");
        TypeInfo type = null;
        
        // TODO
        // F'ing A. To generate type, we need the ClassInfo, which needs to be built.
        // Why??
        // attributes.getValue("type")
        // See Converter.obtainType(Type t)
        
        FieldInfo fInfo =
            new FieldInfo(attributes.getValue("name"), mCurrentClass, mCurrentClass, isPublic,
            isProtected, isPackagePrivate, isPrivate, Boolean.valueOf(attributes.getValue("final")),
            Boolean.valueOf(attributes.getValue("static")), Boolean.valueOf(attributes.
            getValue("transient")), Boolean.valueOf(attributes.getValue("volatile")), false,
            type, "", attributes.getValue("value"), SourcePositionInfo
            .fromXml(attributes.getValue("source")), new AnnotationInstanceInfo[] {});

        mCurrentClass.addField(fInfo);
      } else if (qName.equals("parameter")) {
        mCurrentMethod.addParameter(new ParameterInfo(attributes.getValue("type"), attributes
            .getValue("name")));
      } else if (qName.equals("exception")) {
        mCurrentMethod.addException(attributes.getValue("type"));
      } else if (qName.equals("implements")) {
        mCurrentClass.addInterface(attributes.getValue("name"));
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      if (qName.equals("method")) {
        mCurrentClass.addMethod((MethodInfo) mCurrentMethod);
      } else if (qName.equals("constructor")) {
        mCurrentClass.addConstructor((ConstructorInfo) mCurrentMethod);
      } else if (qName.equals("class") || qName.equals("interface")) {
        mCurrentPackage.addClass(mCurrentClass);
        mCurrentClass = mClassScope.pop();
      } else if (qName.equals("package")) {
        mApi.addPackage(mCurrentPackage);
      }
    }

    public ApiInfo getApi() {
      return mApi;
    }
  }
}
