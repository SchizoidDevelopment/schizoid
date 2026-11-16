import imgui.*;
import imgui.internal.ImGuiContext;
import java.util.*;
public class TwoAtlas {
 static String dir;
 static short[] range(){return new short[]{0x20,0xff,0x400,0x52f,0};}
 static void check(ImFont f){for(int c=0x410;c<=0x44f;c++) if(f.findGlyphNoFallback(c).ptr==0) throw new AssertionError(Integer.toHexString(c));}
 public static void main(String[] x){
  if (x.length != 1) throw new IllegalArgumentException("usage: TwoAtlas <font-directory>");
  dir=java.nio.file.Paths.get(x[0]).toAbsolutePath().toString()+java.io.File.separator;
  ImGuiContext c1=new ImGuiContext(ImGui.createContext().ptr); ImFontAtlas a1=new ImFontAtlas(ImGui.getIO().getFonts().ptr);
  short[] r1=range(); ImFont f1=a1.addFontFromFileTTF(dir+"OpenSans-Regular.ttf",18,r1);
  ImGuiContext c2=new ImGuiContext(ImGui.createContext().ptr); ImGui.setCurrentContext(c2); ImFontAtlas a2=new ImFontAtlas(ImGui.getIO().getFonts().ptr);
  ImFontConfig cfg=new ImFontConfig(); short[] r2=range(); ImFont f2=a2.addFontFromFileTTF(dir+"OpenSans-Bold.ttf",18,cfg,r2);
  if (a2.addFontFromFileTTF(dir+"nonexistent-file.ttf",18,range())!=null) throw new AssertionError("missing file");
  if (a2.addFontFromFileTTF(dir+"nonexistent-file.ttf",18,cfg,range())!=null) throw new AssertionError("missing file config");
  Arrays.fill(r1,(short)0); Arrays.fill(r2,(short)0); System.gc();
  ImGui.setCurrentContext(c1); if(!a1.build()) throw new AssertionError(); check(f1);
  ImGui.setCurrentContext(c2); if(!a2.build()) throw new AssertionError(); check(f2);
  ImGui.destroyContext(c1); if(!a2.build()) throw new AssertionError(); check(f2);
  cfg.destroy(); ImGui.destroyContext(c2);
  System.out.println("PASS: two concurrently live contexts, independent atlases, interleaved builds and first destruction; nonexistent file both overloads returned null");
 }
}
