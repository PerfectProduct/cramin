// Adapted from R8 c331a820's GenerateCustomConversion and DesugaredLibraryJDK11Undesugarer.
// Copyright (c) 2021, 2023, the R8 project authors. All rights reserved.
// BSD-3-Clause; see the packaged R8 LICENSE and AUTHORS.
package com.android.tools.r8.desugar.desugaredlibrary.customconversion;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Runs the upstream transforms without compiling R8's entire test suite. */
public class BytecodeTransforms {
  public static void main(String[] args) throws Exception {
    boolean conversion = args[0].equals("conversion");
    if (!conversion && !args[0].equals("undesugar")) throw new IllegalArgumentException(args[0]);
    Map<String,String> owners = conversion ?
        CustomConversionAsmRewriteDescription.getWrapConvertOwnerMap() : new HashMap<>();
    if (!conversion) {
      String source = Files.readString(Paths.get(args[3]));
      Matcher matcher = Pattern.compile("\\.put\\(\"([^\"]+)\", \"([^\"]+)\"\\)").matcher(source);
      while (matcher.find()) owners.put(matcher.group(1), matcher.group(2));
      if (owners.size() != 11) throw new IllegalStateException("Unexpected upstream ownerMap");
    }
    try (ZipFile input = new ZipFile(args[1]);
         ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(Paths.get(args[2])))) {
      Enumeration<? extends ZipEntry> entries = input.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement(); String name = entry.getName();
        if (!name.endsWith(".class")) continue;
        if (!conversion && (name.equals("sun/nio/fs/DefaultFileSystemProvider.class") ||
            name.equals("sun/nio/fs/DefaultFileTypeDetector.class"))) continue;
        ClassReader reader = new ClassReader(input.getInputStream(entry));
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
          @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
              String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9,
                super.visitMethod(access, name, descriptor, signature, exceptions)) {
              @Override public void visitMethodInsn(int opcode, String owner, String method,
                  String descriptor, boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC) {
                  if (conversion && method.equals("wrap_convert")) {
                    String first = descriptor.substring(2, descriptor.indexOf(';'));
                    if (!owners.containsKey(first)) throw new IllegalStateException(first);
                    super.visitMethodInsn(opcode, owners.get(first), "convert", descriptor, isInterface);
                    return;
                  } else if (!conversion && owners.containsKey(owner)) {
                    String dest = owners.get(owner);
                    int end = descriptor.indexOf(';'), start = descriptor.indexOf('L');
                    String first = end == -1 ? "NoFirstType" : descriptor.substring(start+1,end);
                    boolean receiver = first.equals(dest);
                    super.visitMethodInsn(receiver ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESTATIC,
                        dest, method, receiver ? "("+descriptor.substring(end+1) : descriptor, isInterface);
                    return;
                  }
                }
                super.visitMethodInsn(opcode,owner,method,descriptor,isInterface);
              }
            };
          }
        }, 0);
        byte[] bytes=writer.toByteArray(); CRC32 crc=new CRC32();crc.update(bytes);
        ZipEntry result=new ZipEntry(name); result.setMethod(ZipEntry.STORED);
        result.setSize(bytes.length);result.setCrc(crc.getValue());result.setTime(0);
        output.putNextEntry(result);output.write(bytes);output.closeEntry();
      }
    }
  }
}
