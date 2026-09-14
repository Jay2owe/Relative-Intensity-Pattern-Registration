/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.api;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import ripr.ScaledFrameSource;
import ripr.core.FrameSource;
import ripr.core.LongitudinalContentMotion;
import ripr.core.LongitudinalVideoRouting;

/** Opt-in experimental image-content detector. It has no access to filenames or channel names. */
public final class LongitudinalVideoDetector {
    private LongitudinalVideoDetector() { }
    public static final String VERSION="r25-evidence-1";
    public static final String[] FEATURE_NAMES;
    static {
        List<String> names=new ArrayList<>(Arrays.asList(AutomaticFilterSelector.IMAGE_FEATURE_NAMES));
        names.add("fine_spatial_contrast_fraction");names.add("positive_detail_fraction");
        names.addAll(Arrays.asList(LongitudinalContentMotion.NAMES));
        FEATURE_NAMES=names.toArray(new String[0]);
    }
    public static final class Evidence {
        public final double[] values;public final int[] frames;public final boolean usable;public final double seconds;
        public Evidence(double[] values,int[] frames,boolean usable,double seconds){this.values=values.clone();this.frames=frames.clone();this.usable=usable;this.seconds=seconds;}
    }
    public static Evidence measure(FrameSource source){
        if(source==null||source.count()<2||Math.min(source.width(),source.height())<32)throw new IllegalArgumentException("At least two single-channel frames of 32 pixels per side required");
        long start=System.nanoTime();double scale=Math.min(1,Math.max(160.0/Math.max(source.width(),source.height()),32.0/Math.min(source.width(),source.height())));
        FrameSource reduced=new ScaledFrameSource(source,scale);int count=Math.min(9,source.count()),w=reduced.width(),h=reduced.height();
        int[] indices=new int[count];float[][] frames=new float[count][];double[][] stats=new double[count][19];int structured=0;
        for(int i=0;i<count;i++){
            indices[i]=(int)Math.round(i*(source.count()-1.0)/(count-1));frames[i]=reduced.plane(indices[i]);
            double[] features=AutomaticFilterSelector.imageFeatures(frames[i],w,h),spatial=LongitudinalContentMotion.spatial(frames[i],w,h);
            System.arraycopy(features,0,stats[i],0,17);stats[i][17]=spatial[0];stats[i][18]=spatial[1];
            if(Double.isFinite(features[15])&&Double.isFinite(features[16])&&Math.min(features[15],features[16])>.10)structured++;
        }
        double[] values=new double[FEATURE_NAMES.length];
        for(int j=0;j<19;j++){List<Double> v=new ArrayList<>();for(double[] row:stats)v.add(row[j]);values[j]=LongitudinalContentMotion.median(v);}
        double[] motion=LongitudinalContentMotion.measure(frames,w,h);System.arraycopy(motion,0,values,19,motion.length);
        return new Evidence(values,indices,structured>=Math.max(2,count/3),(System.nanoTime()-start)/1e9);
    }
    public static final class Node {
        public final int feature,left,right,support;public final double threshold,purity;public final String recipeKey;
        public Node(int feature,double threshold,int left,int right,String recipeKey,int support,double purity){
            this.feature=feature;this.threshold=threshold;this.left=left;this.right=right;this.recipeKey=recipeKey;this.support=support;this.purity=purity;
        }
    }
    public static final class Model {
        public final String id;public final Node[] nodes;
        public final double[][] examples;
        public final String[] keys,groups;
        public final String confidenceRule;
        private static final int[] DISTANCE_FEATURES={0,15,17,18};
        public Model(String id,Node[] nodes){
            if(id==null||id.isEmpty()||nodes==null||nodes.length==0)throw new IllegalArgumentException("Missing model");
            this.id=id;this.nodes=nodes.clone();examples=null;keys=null;groups=null;confidenceRule="feature_range";validate(0,new HashSet<>());
        }
        public Model(String id,double[][] examples,String[] keys,String[] groups){
            this(id,examples,keys,groups,"feature_range");
        }
        public Model(String id,double[][] examples,String[] keys,String[] groups,String confidenceRule){
            if(id==null||id.isEmpty()||examples==null||keys==null||groups==null||examples.length!=keys.length||keys.length!=groups.length||keys.length<3)throw new IllegalArgumentException("Invalid example model");
            if(!"feature_range".equals(confidenceRule)&&!"recording_radius".equals(confidenceRule))throw new IllegalArgumentException("Unknown confidence rule");
            this.confidenceRule=confidenceRule;
            this.id=id;nodes=null;this.examples=new double[examples.length][];this.keys=keys.clone();this.groups=groups.clone();
            for(int i=0;i<examples.length;i++){if(examples[i]==null||examples[i].length!=FEATURE_NAMES.length)throw new IllegalArgumentException("Example width mismatch");this.examples[i]=examples[i].clone();LongitudinalVideoRouting.routeFor(keys[i]);if(groups[i]==null||groups[i].isEmpty()||groups[i].contains(",")||groups[i].contains("\n")||groups[i].contains("\r"))throw new IllegalArgumentException("Valid recording group required");for(int f:DISTANCE_FEATURES)if(!Double.isFinite(value(examples[i],f)))throw new IllegalArgumentException("Example evidence missing");}
        }
        private void validate(int index,Set<Integer> path){
            if(index<0||index>=nodes.length||!path.add(index))throw new IllegalArgumentException("Invalid/cyclic model");
            Node n=nodes[index];
            if(n.feature<0){LongitudinalVideoRouting.routeFor(n.recipeKey);if(n.support<1||!Double.isFinite(n.purity)||n.purity<0||n.purity>1)throw new IllegalArgumentException("Invalid leaf");}
            else{if(n.feature>=FEATURE_NAMES.length||!Double.isFinite(n.threshold))throw new IllegalArgumentException("Invalid split");validate(n.left,new HashSet<>(path));validate(n.right,new HashSet<>(path));}
        }
        public void save(Path file) throws IOException {
            Properties p=new Properties();p.setProperty("version",VERSION);p.setProperty("id",id);p.setProperty("confidence_rule",confidenceRule);
            if(examples==null){p.setProperty("nodes",Integer.toString(nodes.length));for(int i=0;i<nodes.length;i++){Node n=nodes[i];p.setProperty("node."+i,n.feature+","+n.threshold+","+n.left+","+n.right+","+n.recipeKey+","+n.support+","+n.purity);}}
            else{p.setProperty("examples",Integer.toString(examples.length));for(int i=0;i<examples.length;i++){StringBuilder row=new StringBuilder(keys[i]+","+groups[i]);for(double v:examples[i])row.append(',').append(v);p.setProperty("example."+i,row.toString());}}
            try(Writer w=Files.newBufferedWriter(file)){p.store(w,"Experimental recipe selector; not a biological identity classifier");}
        }
        public static Model load(Path file) throws IOException {
            Properties p=new Properties();try(Reader r=Files.newBufferedReader(file)){p.load(r);}
            if(!VERSION.equals(p.getProperty("version")))throw new IllegalArgumentException("Feature version mismatch");
            if(p.containsKey("examples")){int n=Integer.parseInt(p.getProperty("examples"));double[][] x=new double[n][FEATURE_NAMES.length];String[] keys=new String[n],groups=new String[n];for(int i=0;i<n;i++){String[] v=p.getProperty("example."+i).split(",",-1);keys[i]=v[0];groups[i]=v[1];for(int f=0;f<FEATURE_NAMES.length;f++)x[i][f]=Double.parseDouble(v[f+2]);}return new Model(p.getProperty("id"),x,keys,groups,p.getProperty("confidence_rule","feature_range"));}
            Node[] nodes=new Node[Integer.parseInt(p.getProperty("nodes"))];
            for(int i=0;i<nodes.length;i++){String[] v=p.getProperty("node."+i).split(",",-1);nodes[i]=new Node(Integer.parseInt(v[0]),Double.parseDouble(v[1]),Integer.parseInt(v[2]),Integer.parseInt(v[3]),v[4],Integer.parseInt(v[5]),Double.parseDouble(v[6]));}
            return new Model(p.getProperty("id"),nodes);
        }
        static double value(double[] row,int f){return f==15?(row[15]+row[16])/2:row[f];}
        static double distance(double[] a,double[] b,double[] scales){
            double sum=0;for(int j=0;j<scales.length;j++){double z=(value(a,DISTANCE_FEATURES[j])-value(b,DISTANCE_FEATURES[j]))/scales[j];sum+=z*z;}return Math.sqrt(sum/scales.length);
        }
        static double nearestThree(Map<String,Double> byGroup){
            double[] v=byGroup.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();double sum=0;for(int i=0;i<Math.min(3,v.length);i++)sum+=v[i];return v.length==0?Double.POSITIVE_INFINITY:sum/Math.min(3,v.length);
        }
        double recordingRadius(String key,double[] scales){
            // Calibration only sees model training rows. Exclude every channel from the same
            // recording when measuring its distance; repeated channels cannot create support.
            Map<String,Double> heldGroupScores=new HashMap<>();
            for(int i=0;i<examples.length;i++)if(keys[i].equals(key)){
                Map<String,Double> others=new HashMap<>();
                for(int j=0;j<examples.length;j++)if(keys[j].equals(key)&&!groups[j].equals(groups[i]))
                    others.merge(groups[j],distance(examples[i],examples[j],scales),Math::min);
                if(others.size()>=3)heldGroupScores.merge(groups[i],nearestThree(others),Math::max);
            }
            if(heldGroupScores.size()<4)return Double.NaN;
            double[] scores=heldGroupScores.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
            return Math.max(1e-6,scores[(int)Math.ceil(.90*scores.length)-1]);
        }
        Decision nearest(Evidence evidence){
            double[] scales=new double[DISTANCE_FEATURES.length];
            for(int j=0;j<scales.length;j++){int f=DISTANCE_FEATURES[j];double[] v=new double[examples.length];for(int i=0;i<v.length;i++)v[i]=value(examples[i],f);Arrays.sort(v);scales[j]=Math.max(1e-6,v[3*(v.length-1)/4]-v[(v.length-1)/4]);if(!Double.isFinite(value(evidence.values,f)))return new Decision("","required spatial evidence unavailable",false,evidence,id);}
            Map<String,Map<String,Double>> distances=new TreeMap<>();
            for(int i=0;i<examples.length;i++){Map<String,Double> byGroup=distances.computeIfAbsent(keys[i],k->new HashMap<>());byGroup.merge(groups[i],distance(examples[i],evidence.values,scales),Math::min);}
            String selected="";double best=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;int support=0;
            for(String key:distances.keySet()){double[] values=distances.get(key).values().stream().mapToDouble(Double::doubleValue).sorted().toArray();double score=0;for(int i=0;i<Math.min(3,values.length);i++)score+=values[i];score/=Math.min(3,values.length);if(score<best){second=best;best=score;selected=key;support=values.length;}else second=Math.min(second,score);}
            boolean inside=true;
            for(int f:DISTANCE_FEATURES){double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;for(int i=0;i<examples.length;i++)if(keys[i].equals(selected)){lo=Math.min(lo,value(examples[i],f));hi=Math.max(hi,value(examples[i],f));}double pad=Math.max(1e-6,.1*(hi-lo)),v=value(evidence.values,f);if(v<lo-pad||v>hi+pad)inside=false;}
            boolean enough=support>=3;
            if(confidenceRule.equals("recording_radius")){double radius=recordingRadius(selected,scales);enough=Double.isFinite(radius);inside=enough&&best<=radius;}
            boolean separated=second>=1.3*Math.max(1e-6,best),confident=enough&&inside&&separated;
            String reason=!enough?"too few distinct recordings for this confidence check":!inside?"outside demonstrated image range or distance":!separated?"competing recipes have similar image evidence":"separated matches supported by recording-level checks";
            return new Decision(selected,reason,confident,evidence,id);
        }
    }
    public static final class Decision {
        public final String recipeKey,reason,modelId;public final boolean confident;public final Evidence evidence;
        Decision(String key,String reason,boolean confident,Evidence evidence,String modelId){recipeKey=key;this.reason=reason;this.confident=confident;this.evidence=evidence;this.modelId=modelId;}
    }
    public static Decision detect(FrameSource source,Model model){return classify(measure(source),model);}
    public static Decision classify(Evidence evidence,Model model){
        if(evidence==null||model==null||evidence.values.length!=FEATURE_NAMES.length)throw new IllegalArgumentException("Evidence and compatible model required");
        if(!evidence.usable)return new Decision("","insufficient coherent image structure",false,evidence,model.id);
        if(model.examples!=null)return model.nearest(evidence);
        int index=0;
        for(int visited=0;visited<=model.nodes.length;visited++){
            Node n=model.nodes[index];
            if(n.feature<0){boolean confident=n.support>=3&&n.purity>=.9;return new Decision(n.recipeKey,confident?"supported development-model leaf":"too few or mixed development examples",confident,evidence,model.id);}
            double value=evidence.values[n.feature];
            if(!Double.isFinite(value))return new Decision("","required image evidence unavailable",false,evidence,model.id);
            index=value<=n.threshold?n.left:n.right;
        }
        throw new IllegalArgumentException("Cyclic model");
    }
}
