import imgui.*;
public class FileOverloads {
 static void expect(Class<? extends Throwable> c, Runnable r) { try { r.run(); throw new AssertionError("missing " + c); } catch (Throwable t) { if (!c.isInstance(t)) throw t; } }
 public static void main(String[] args) {
  if (args.length != 1) throw new IllegalArgumentException("usage: FileOverloads <font-directory>");
  ImGui.createContext(); ImFontAtlas a=ImGui.getIO().getFonts(); ImFontConfig c=new ImFontConfig(); String f=java.nio.file.Paths.get(args[0], "OpenSans-Regular.ttf").toAbsolutePath().toString();
  if(a.addFontFromFileTTF(f,18).ptr==0 || a.addFontFromFileTTF(f,18,c).ptr==0) throw new AssertionError();
  short[] r=a.getGlyphRangesCyrillic(); if(r[r.length-1]!=0) throw new AssertionError("terminator");
  ImFont x=a.addFontFromFileTTF(f,18,r); if(x.ptr==0 || a.addFontFromFileTTF(f,18,c,r).ptr==0) throw new AssertionError();
  expect(NullPointerException.class,()->a.addFontFromFileTTF(null,18));
  expect(IllegalArgumentException.class,()->a.addFontFromFileTTF(f,18,new short[]{1,2}));
  expect(IllegalArgumentException.class,()->a.addFontFromFileTTF(f,18,new short[]{5,2,0}));
  expect(IllegalArgumentException.class,()->a.addFontFromFileTTF(f,Float.NaN));
  if(!a.build() || x.findGlyphNoFallback(0x416).ptr==0 || !a.build()) throw new AssertionError("build");
  c.destroy(); ImGui.destroyContext(); System.out.println("PASS: four file overloads, getter terminator, bad ranges/null/NaN, Cyrillic and rebuild");
 }
}
