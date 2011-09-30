/*
* Copyright 2011 Jeanfrancois Arcand
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/
/*
 * Copyright 2009 Richard Zschech.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.gwt.rebind;

import com.google.gwt.core.ext.GeneratorContextExt;
import com.google.gwt.core.ext.GeneratorExt;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JField;
import com.google.gwt.core.ext.typeinfo.JType;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dev.javac.rebind.RebindResult;
import com.google.gwt.dev.javac.rebind.RebindStatus;
import com.google.gwt.dev.util.collect.Lists;
import com.google.gwt.rpc.linker.RpcDataArtifact;
import com.google.gwt.user.client.rpc.impl.Serializer;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import com.google.gwt.user.rebind.rpc.SerializableTypeOracle;
import com.google.gwt.user.rebind.rpc.SerializableTypeOracleBuilder;
import com.google.gwt.user.rebind.rpc.SerializationUtils;
import com.google.gwt.user.rebind.rpc.TypeSerializerCreator;
import org.atmosphere.gwt.client.SerialMode;
import org.atmosphere.gwt.client.SerialTypes;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.List;

public class SerializerGenerator extends GeneratorExt {

    @Override
    public RebindResult generateIncrementally(TreeLogger logger, GeneratorContextExt context, String typeName) throws UnableToCompleteException {

        TypeOracle typeOracle = context.getTypeOracle();

        // Create the CometSerializer impl
        String packageName = "comet";
        String className = typeName.replace('.', '_') + "Impl";
        PrintWriter printWriter = context.tryCreate(logger, packageName, className);

        if (printWriter != null) {

            try {
                JClassType type = typeOracle.getType(typeName);
                SerialTypes annotation = type.getAnnotation(SerialTypes.class);
                if (annotation == null) {
                    logger.log(TreeLogger.ERROR, "No SerialTypes annotation on CometSerializer type: " + typeName);
                    throw new UnableToCompleteException();
                }

                SerializableTypeOracleBuilder typesSentToBrowserBuilder = new SerializableTypeOracleBuilder(
                        logger, context.getPropertyOracle(), context);
                SerializableTypeOracleBuilder typesSentFromBrowserBuilder = new SerializableTypeOracleBuilder(
                        logger, context.getPropertyOracle(), context);

                for (Class<? extends Serializable> serializable : annotation.value()) {
                    int rank = 0;
                    if (serializable.isArray()) {
                        while (serializable.isArray()) {
                            serializable = (Class<? extends Serializable>) serializable.getComponentType();
                            rank++;
                        }
                    }

                    JType resolvedType = typeOracle.getType(serializable.getCanonicalName());
                    while (rank > 0) {
                        resolvedType = typeOracle.getArrayType(resolvedType);
                        rank--;
                    }

                    typesSentToBrowserBuilder.addRootType(logger, resolvedType);
                    typesSentFromBrowserBuilder.addRootType(logger, resolvedType);
                }

                // Create a resource file to receive all of the serialization information
                // computed by STOB and mark it as private so it does not end up in the
                // output.
                OutputStream pathInfo = context.tryCreateResource(logger, typeName + ".rpc.log");
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(pathInfo));
                writer.write("====================================\n");
                writer.write("Types potentially sent from server:\n");
                writer.write("====================================\n\n");
                writer.flush();

                typesSentToBrowserBuilder.setLogOutputWriter(writer);
                SerializableTypeOracle typesSentToBrowser = typesSentToBrowserBuilder.build(logger);

                writer.write("===================================\n");
                writer.write("Types potentially sent from browser:\n");
                writer.write("===================================\n\n");
                writer.flush();
                typesSentFromBrowserBuilder.setLogOutputWriter(writer);
                SerializableTypeOracle typesSentFromBrowser = typesSentFromBrowserBuilder.build(logger);

                writer.close();

                if (pathInfo != null) {
                    context.commitResource(logger, pathInfo).setPrivate(true);
                }

                // Create the serializer
                final String modifiedTypeName = typeName.replace('.', '_');
                TypeSerializerCreator tsc = new TypeSerializerCreator(logger, typesSentFromBrowser, typesSentToBrowser, context, "comet." + modifiedTypeName, modifiedTypeName);
                String realize = tsc.realize(logger);

                // Create the CometSerializer impl
                ClassSourceFileComposerFactory composerFactory = new ClassSourceFileComposerFactory(packageName, className);

                composerFactory.addImport(Serializer.class.getName());
                composerFactory.addImport(SerialMode.class.getName());

                composerFactory.setSuperclass(typeName);
                // TODO is the SERIALIZER required for DE RPC?
                SourceWriter sourceWriter = composerFactory.createSourceWriter(context, printWriter);
                sourceWriter.print("private Serializer SERIALIZER = new " + realize + "();");
                sourceWriter.print("protected Serializer getSerializer() {return SERIALIZER;}");
                sourceWriter.print("public SerialMode getMode() {return SerialMode." + annotation.mode().name() + ";}");
                sourceWriter.print("public SerialMode getPushMode() {return SerialMode." + annotation.pushmode().name() + ";}");
                sourceWriter.commit(logger);

                if (annotation.mode() == SerialMode.DE_RPC) {
                    RpcDataArtifact data = new RpcDataArtifact(type.getQualifiedSourceName());
                    for (JType t : typesSentToBrowser.getSerializableTypes()) {
                        if (!(t instanceof JClassType)) {
                            continue;
                        }
                        JField[] serializableFields = SerializationUtils.getSerializableFields(context.getTypeOracle(), (JClassType) t);

                        List<String> names = Lists.create();
                        for (int i = 0, j = serializableFields.length; i < j; i++) {
                            names = Lists.add(names, serializableFields[i].getName());
                        }

                        data.setFields(SerializationUtils.getRpcTypeName(t), names);
                    }

                    context.commitArtifact(logger, data);
                }
            } catch (NotFoundException e) {
                logger.log(TreeLogger.ERROR, "", e);
                throw new UnableToCompleteException();
            }
        }

        return new RebindResult(RebindStatus.USE_PARTIAL_CACHED, packageName + '.' + className);
    }
}
