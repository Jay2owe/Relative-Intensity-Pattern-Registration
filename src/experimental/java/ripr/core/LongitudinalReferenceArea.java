/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

/** Reference-order translation-only area search, reusing existing spline preparation. */
final class LongitudinalReferenceArea {
    private LongitudinalReferenceArea() { }

    static PairAligner.Options options(int width,int height) {
        PairAligner.Options o=new PairAligner.Options();
        o.maxLevels=9;o.minCoarseSize=24;o.maxShift=Math.max(30,.6*Math.hypot(width,height));
        o.coarseRadiusBudget=8;o.norm=RobustNorm.LEAST_SQUARES;o.profileGain=true;
        o.support=PairAligner.PixelSupport.ALL;o.fitRotation=false;o.maxIterations=25;
        o.convergence=1e-4;o.minValidFraction=.1;o.maxSamples=200000;
        return o;
    }

    static PairAligner.Fit align(LogPlane[] a,LogPlane[] b,Transform start,PairAligner.Options o) {
        PairAligner.validatePyramids(a,b);
        int top=a.length-1;Transform current=clamp(start.scaleTranslation(1./(1<<top)),o.maxShift/(1<<top));
        Scratch scratch=new Scratch(a[0].width*a[0].height);
        for(int level=top;level>=0;level--) {
            AreaCorrelation.Window left=AreaCorrelation.Window.of(a[level],o),right=AreaCorrelation.Window.of(b[level],o);
            double max=o.maxShift/(1<<level),cx=Math.rint(current.dx),cy=Math.rint(current.dy),best=Double.NEGATIVE_INFINITY;
            Transform winner=current;
            // Reference traverses every candidate in row order, including the centre.
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++) {
                double xx=cx+dx,yy=cy+dy;
                if(Math.hypot(xx,yy)>max+1e-9)continue;
                double score=score(left,right,xx,yy,o,false,scratch);
                if(Double.isFinite(score) && score>best) {best=score;winner=Transform.translation(xx,yy);}
            }
            current=winner;
            if(level>0)current=clamp(current.scaleTranslation(2),o.maxShift/(1<<(level-1)));
        }
        AreaCorrelation.Window left=AreaCorrelation.Window.of(a[0],o),right=AreaCorrelation.Window.of(b[0],o);
        Transform refined=ecc(left,right,current,o,scratch);
        if(refined==null)refined=grid(left,right,current,o,scratch);
        PairAligner.Status status=refined.magnitude()>=o.maxShift*(1-1e-9)?PairAligner.Status.AT_SHIFT_BOUND:
            scratch.converged?PairAligner.Status.OK:PairAligner.Status.NOT_CONVERGED;
        return PairAligner.reportArea(a,b,o,refined,scratch.iterations,status);
    }

    static final class Scratch {
        final double[] a,b,gx,gy;
        final double[] sampled=new double[3],weights=new double[16];
        int count,possible,iterations;boolean converged;
        Scratch(int n) {a=new double[n];b=new double[n];gx=new double[n];gy=new double[n];}
    }

    static void samples(AreaCorrelation.Window a,AreaCorrelation.Window b,double dx,double dy,
                        boolean interior,boolean gradient,Scratch s) {
        int n=0,possible=0;boolean whole=!interior && dx==Math.rint(dx) && dy==Math.rint(dy);
        int ix=(int)Math.rint(dx),iy=(int)Math.rint(dy);
        for(int y=0;y<a.height;y+=a.stride)for(int x=0;x<a.width;x+=a.stride) {
            possible++;int p=y*a.width+x;if(!a.plane.valid[p])continue;
            double value;
            if(gradient) {
                if(!b.sampleWithGradient(x+dx,y+dy,s.sampled,s.weights))continue;
                value=s.sampled[0];s.gx[n]=s.sampled[1];s.gy[n]=s.sampled[2];
            } else if(whole) {
                int xx=x+ix,yy=y+iy;if(xx<0 || yy<0 || xx>=b.width || yy>=b.height)continue;
                int q=yy*b.width+xx;if(!b.plane.valid[q])continue;value=b.value[q];
            } else value=interior?b.sampleInterior(x+dx,y+dy,s.weights):b.sample(x+dx,y+dy);
            if(!Double.isFinite(value))continue;
            s.a[n]=a.value[p];s.b[n]=value;n++;
        }
        s.count=n;s.possible=possible;
    }

    static double mean(double[] values,int n) {double sum=0;for(int i=0;i<n;i++)sum+=values[i];return sum/n;}
    static double dot(double[] a,double[] b,int n) {double sum=0;for(int i=0;i<n;i++)sum+=a[i]*b[i];return sum;}
    static double centre(double[] values,int n) {double mean=mean(values,n);for(int i=0;i<n;i++)values[i]-=mean;return mean;}

    static double score(AreaCorrelation.Window a,AreaCorrelation.Window b,double dx,double dy,
                        PairAligner.Options o,boolean interior,Scratch s) {
        samples(a,b,dx,dy,interior,false,s);int n=s.count;
        if(n<o.minValidFraction*s.possible || n<(interior?8:1))return Double.NaN;
        centre(s.a,n);centre(s.b,n);
        double denominator=Math.sqrt(dot(s.a,s.a,n)*dot(s.b,s.b,n));
        return denominator>0?dot(s.a,s.b,n)/denominator:Double.NaN;
    }

    private static Transform ecc(AreaCorrelation.Window a,AreaCorrelation.Window b,Transform start,
                                 PairAligner.Options o,Scratch s) {
        double x=start.dx,y=start.dy,move=Double.POSITIVE_INFINITY;int acceptedSteps=0;s.iterations=0;
        for(int i=0;i<Math.max(1,Math.min(o.maxIterations,8));i++) {
            samples(a,b,x,y,true,true,s);int n=s.count;
            if(n<Math.max(8,o.minValidFraction*s.possible))break;
            centre(s.a,n);centre(s.b,n);
            double va=dot(s.a,s.a,n),vb=dot(s.b,s.b,n);
            if(!(va>0 && vb>0))break;
            double px=dot(s.a,s.gx,n),py=dot(s.a,s.gy,n),qx=dot(s.b,s.gx,n),qy=dot(s.b,s.gy,n);
            centre(s.gx,n);centre(s.gy,n);
            double hxx=dot(s.gx,s.gx,n),hyy=dot(s.gy,s.gy,n),hxy=dot(s.gx,s.gy,n);
            double determinant=hxx*hyy-hxy*hxy;if(!(determinant>0 && hxx>0))break;
            double hqx=(hyy*qx-hxy*qy)/determinant,hqy=(hxx*qy-hxy*qx)/determinant;
            double numerator=vb-qx*hqx-qy*hqy,cross=dot(s.a,s.b,n),denominator=cross-px*hqx-py*hqy;
            if(!(numerator>0 && denominator>0))break;
            double scale=numerator/denominator,ex=scale*px-qx,ey=scale*py-qy;
            double stepX=(hyy*ex-hxy*ey)/determinant,stepY=(hxx*ey-hxy*ex)/determinant;
            double length=Math.hypot(stepX,stepY);if(!Double.isFinite(length))break;
            if(length>1) {stepX/=length;stepY/=length;length=1;}
            double correlation=cross/Math.sqrt(va*vb);s.iterations++;boolean accepted=false;double factor=1;
            for(int j=0;j<4;j++) {
                double xx=x+factor*stepX,yy=y+factor*stepY;
                if(Math.hypot(xx,yy)<=o.maxShift+1e-9) {
                    double trial=score(a,b,xx,yy,o,true,s);
                    if(Double.isFinite(trial) && trial>=correlation) {x=xx;y=yy;accepted=true;acceptedSteps++;break;}
                }
                factor*=.5;
            }
            if(!accepted)break;
            move=factor*length;if(move<Math.max(o.convergence,1./256))break;
        }
        s.converged=move<Math.max(o.convergence,1./256);
        return acceptedSteps==0?null:Transform.translation(x,y);
    }

    static Transform grid(AreaCorrelation.Window a,AreaCorrelation.Window b,Transform start,
                                  PairAligner.Options o,Scratch s) {
        Transform current=start;double step=1,move=Double.POSITIVE_INFINITY;
        double centre=score(a,b,current.dx,current.dy,o,false,s);
        for(int iteration=0;iteration<Math.max(1,Math.min(o.maxIterations,5));iteration++) {
            s.iterations=iteration;
            double best=Double.NEGATIVE_INFINITY;Transform winner=current;
            for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++) {
                double x=current.dx+i*step,y=current.dy+j*step;
                if(Math.hypot(x,y)>o.maxShift+1e-9)continue;
                double value=score(a,b,x,y,o,false,s);
                if(Double.isFinite(value) && value>best) {best=value;winner=Transform.translation(x,y);}
            }
            if(!Double.isFinite(best))break;
            if(best>centre) {move=Math.hypot(winner.dx-current.dx,winner.dy-current.dy);current=winner;centre=best;}
            else step*=.25;
            if(move<Math.max(o.convergence,1./256))break;
        }
        s.converged=move<Math.max(o.convergence,1./256);return current;
    }

    private static Transform clamp(Transform value,double bound) {
        double magnitude=value.magnitude();return magnitude>bound && magnitude!=0?value.scaleTranslation(bound/magnitude):value;
    }
}
