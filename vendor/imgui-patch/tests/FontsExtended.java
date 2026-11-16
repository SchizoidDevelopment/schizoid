import imgui.*;
import java.util.Arrays;
public class FontsExtended {
 public static void main(String[] args) {
  if (args.length != 1) throw new IllegalArgumentException("usage: FontsExtended <font-directory>");
  String dir=java.nio.file.Paths.get(args[0]).toAbsolutePath().toString()+java.io.File.separator;
  int[] cps=new int[67]; for(int x=0;x<64;x++) cps[x]=0x410+x; cps[64]=0x401; cps[65]=0x451; cps[66]=0xf007;
  for(int i=0;i<20;i++) {
   ImGui.createContext(); ImFontAtlas a=ImGui.getIO().getFonts();
   short[] c={0x20,0xff,0x400,0x52f,0}; short[] icons={(short)0xe005,(short)0xf8ff,0};
   ImFont f=a.addFontFromFileTTF(dir+((i%2==0)?"OpenSans-Regular.ttf":"OpenSans-Bold.ttf"),18,c);
   ImFontConfig cfg=new ImFontConfig(); cfg.setMergeMode(true);
   ImFont m=a.addFontFromFileTTF(dir+"fa-solid-900.ttf",18,cfg,icons);
   if(f.ptr==0 || m.ptr==0) throw new AssertionError("load");
   Arrays.fill(c,(short)0); Arrays.fill(icons,(short)0); System.gc();
   for(int j=0;j<2;j++) { if(!a.build()) throw new AssertionError("build "+j); for(int cp:cps) if(f.findGlyphNoFallback(cp).ptr==0) throw new AssertionError("glyph "+Integer.toHexString(cp)+" build="+j); }
   if(i%2==0) a.clearInputData(); else a.clear(); cfg.destroy(); ImGui.destroyContext();
  }
  System.out.println("PASS: 20 contexts; Regular/Bold; U+0410 U+044F U+0401 U+0451 U+F007 after each of 2 builds; arrays overwritten, GC, clearInputData/clear, config/context destroy");
 }
}
