package org.timepedia.exporter.doclet;
/*
import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.Parameter;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Type;
import com.sun.tools.doclets.formats.html.ConfigurationImpl;
import com.sun.tools.doclets.formats.html.HtmlDoclet;
import com.sun.tools.doclets.formats.html.HtmlDocletWriter;
import com.sun.tools.doclets.internal.toolkit.util.ClassTree;
import com.sun.tools.doclets.internal.toolkit.util.DocletConstants;
import com.sun.tools.doclets.internal.toolkit.util.PackageListWriter;
import com.sun.tools.doclets.internal.toolkit.util.Util;
*/

/**
 * Doclet to generate documentation for exported Javascript methods.
 */
public class JsDoclet /*extends HtmlDoclet*/ {
/*
  public static boolean start(RootDoc rootDoc) {
    JsDoclet jsDoclet = new JsDoclet();

    try {
      return jsDoclet.startGeneration2(rootDoc);
    } catch (Exception e) {
      e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
    }
    return false;
  }

  private boolean startGeneration2(RootDoc root) throws Exception {
    configuration = ConfigurationImpl.getInstance();
    configuration.root = root;

    if (root.classes().length == 0) {
      configuration.message.
          error("doclet.No_Public_Classes_To_Document");
      return false;
    }
    configuration.setOptions();
    configuration.getDocletSpecificMsg().notice("doclet.build_version",
        configuration.getDocletSpecificBuildDate());
    ClassTree classtree = new ClassTree(configuration,
        configuration.nodeprecated);

    generateClassFiles(root, classtree);
    if (configuration.sourcepath != null
        && configuration.sourcepath.length() > 0) {
      StringTokenizer pathTokens = new StringTokenizer(configuration.sourcepath,
          String.valueOf(File.pathSeparatorChar));
      boolean first = true;
      while (pathTokens.hasMoreTokens()) {
        Util.copyDocFiles(configuration,
            pathTokens.nextToken() + File.separator,
            DocletConstants.DOC_FILES_DIR_NAME, first);
        first = false;
      }
    }

    PackageListWriter.generate(configuration);
    generatePackageFiles(classtree);

    generateOtherFiles(root, classtree);
    configuration.tagletManager.printReport();
    return true;
  }

  @Override
  protected void generateOtherFiles(RootDoc rootDoc, ClassTree classTree)
      throws Exception {
    super.generateOtherFiles(rootDoc, classTree);
    HtmlDocletWriter writer = new HtmlDocletWriter(configuration, "jsdoc.html");

    writer.html();
    writer.body("white", true);
    for (ClassDoc clz : rootDoc.classes()) {
      if (isExportable(clz)) {
        int countExportedMethods = 0;
        for (ConstructorDoc cd : clz.constructors()) {
          if (isExportable(cd)) {
            countExportedMethods++;
          }
        }

        for (MethodDoc md : clz.methods()) {
          if (isExportable(md)) {
            countExportedMethods++;
          }
        }

        if (countExportedMethods == 0 || isExportedClosure(clz.methods()[0])) {
          continue;
        }

        writer.h3("Package: " + getExportedPackage(clz));
        writer.h2("Class " + getExportedName(clz));
        writer.println(filter(clz.commentText()));
        writer.br();
        writer.br();

        writer.table(1, "100%", 0, 0);

        boolean firstcon = true;

        for (ConstructorDoc cd : clz.constructors()) {
          if (isExportable(cd)) {
            if (firstcon) {
              writer.tr();
              writer.tdColspanBgcolorStyle(2, "lightblue", "jsdocHeader");
              writer.print("Constructors");
              firstcon = false;
              writer.tdEnd();
              writer.trEnd();
            }
            writer.tr();
            writer.td();
            writer.tdEnd();
            writer.tdVAlignClass("top", "jsdocMethod");
            writer.print("<b class=jsdocMethodName>" + cd.name() + "</b>(");
            writeParameters(writer, cd.parameters());
            writer.print(")");
            writer.br();
            writer.print("&nbsp;&nbsp;&nbsp;&nbsp;<span class=jsdocComment>"
                + filter(cd.commentText()) + "</span>");

            writer.tdEnd();
            writer.trEnd();
          }
        }

        for (MethodDoc cd : clz.methods()) {
          if (isExportable(cd)) {
            if (firstcon) {
              writer.tr();
              writer.tdColspanBgcolorStyle(2, "lightblue", "jsdocHeader");
              writer.print("Methods");
              firstcon = false;
              writer.tdEnd();
              writer.trEnd();
            }
            writer.tr();
            writer.tdVAlignClass("top", "jsdocRetType");
            writer.print(getExportedName(cd.returnType()));
            writer.tdEnd();
            writer.tdVAlignClass("top", "jsdocMethod");
            writer.print(
                "<b class=jsdocMethodName>" + getExportedName(cd) + "</b>"
                    + "(");
            writeParameters(writer, cd.parameters());
            writer.print(")");
            writer.br();
            writer.print("&nbsp;&nbsp;&nbsp;&nbsp;<span class=jsdocComment>"
                + filter(cd.commentText()) + "</span>");
            writer.tdEnd();
            writer.trEnd();
          }
        }

        writer.tableEnd();
        writer.br();
        writer.hr();
      }
    }
    writer.bodyEnd();
    writer.htmlEnd();
    writer.flush();
    writer.close();
  }

  private String getExportedName(MethodDoc cd) {

    String ename = cd.name();

    for (AnnotationDesc a : cd.annotations()) {
      if (a.annotationType().name().equals("Export")) {
        for (AnnotationDesc.ElementValuePair p : a.elementValues()) {
          ename = p.value().toString();
          break;
        }
      }
    }
    return ename.replaceAll("\"", "");
  }

  private String filter(String s) {
    if (s.startsWith("Created")) {
      return "";
    }
    return s;
  }

  private String getExportedName(Type clz) {
    return clz.isPrimitive() ? clz.typeName()
        : getExportedName(clz.asClassDoc());
  }

  private void writeParameters(HtmlDocletWriter writer, Parameter[] ps) {
    writer.print(getParameterString(ps));
  }

  private String getParameterString(Parameter[] ps) {
    String result = "";
    for (int i = 0; i < ps.length; i++) {
      Type type = ps[i].type();
      String ename = type.isPrimitive() ? type.typeName()
          : getExportedName(type.asClassDoc());
      result += "<span class=jsdocParameterType>" + ename
          + "</span> <span class=jsdocParameterName>" + ps[i].name()
          + "</span>";
      if (i < ps.length - 1) {
        result += ", ";
      }
    }
    return result;
  }

  private boolean isExportable(ConstructorDoc cd) {
    boolean export = isExported(cd.containingClass());
    for (AnnotationDesc a : cd.annotations()) {
      if (a.annotationType().name().equals("Export")) {
        export = true;
      }
      if (a.annotationType().name().equals("NoExport")) {
        export = false;
      }
    }
    return export;
  }

  private boolean isExportable(MethodDoc cd) {
    boolean export = isExported(cd.containingClass());
    for (AnnotationDesc a : cd.annotations()) {
      if (a.annotationType().name().equals("Export")) {
        export = true;
      }
      if (a.annotationType().name().equals("NoExport")) {
        export = false;
      }
    }
    return export;
  }

  private boolean isExportedClosure(MethodDoc md) {
    ClassDoc clz = md.containingClass();
    for (AnnotationDesc a : clz.annotations()) {

      String aname = a.annotationType().name();
      if (aname.equals("ExportClosure")) {
        return true;
      }
    }
    return false;
  }

  private boolean isExported(ClassDoc clz) {

    for (AnnotationDesc a : clz.annotations()) {

      String aname = a.annotationType().name();
      if (aname.equals("Export") || aname.equals("ExportClosure")) {
        return true;
      }
    }
    return false;
  }

  private String getExportedName(ClassDoc clz) {
    if (clz == null) {
      return "";
    }

    PackageDoc cpkg = clz.containingPackage();
    String pkg = cpkg == null ? "" : (cpkg.name() + ".");
    String name = clz.name();

    for (AnnotationDesc a : clz.annotations()) {
      if (a.annotationType().name().equals("ExportPackage")) {
        for (AnnotationDesc.ElementValuePair p : a.elementValues()) {
          pkg = p.value().toString();
          break;
        }
      }
      if (a.annotationType().name().equals("Export")) {
        for (AnnotationDesc.ElementValuePair p : a.elementValues()) {
          name = p.value().toString();
          break;
        }
      }
      if (a.annotationType().name().equals("ExportClosure")) {
        name = "<i class=jsdocClosureFunc>function</i>(";
        name += getParameterString(clz.methods()[0].parameters());
        name += ")";
        pkg = "";
      }
    }
    pkg = pkg.replaceAll("\"", "");
    return name;
  }

  private String getExportedPackage(ClassDoc clz) {
    if (clz == null) {
      return "";
    }

    PackageDoc cpkg = clz.containingPackage();
    String pkg = cpkg == null ? "" : (cpkg.name());

    for (AnnotationDesc a : clz.annotations()) {
      if (a.annotationType().name().equals("ExportPackage")) {
        for (AnnotationDesc.ElementValuePair p : a.elementValues()) {
          pkg = p.value().toString();
          break;
        }
      }
    }
    pkg = pkg.replaceAll("\"", "");
    return pkg;
  }

  private static void printClass(ClassDoc clz) {
    p(clz.name());
  }

  private static void p(String str) {
    System.out.println(str);
  }

  private static boolean isExportable(ClassDoc clz) {
    for (ClassDoc i : clz.interfaces()) {
      if (i.name().contains("Exportable")) {
        return true;
      }
    }
    return false;
  }*/
}
