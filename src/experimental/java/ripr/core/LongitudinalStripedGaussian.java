/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

/** Candidate only: retain each pixel's sum order while processing adjacent pixels together. */
final class LongitudinalStripedGaussian {
    private LongitudinalStripedGaussian() { }
    static float[] filter(float[] source,int width,int height,double sigma) {
        int radius=(int)(4*sigma+.5);double[] kernel=new double[2*radius+1];double sum=0;
        for(int i=-radius;i<=radius;i++) {kernel[i+radius]=Math.exp(-.5*i*i/(sigma*sigma));sum+=kernel[i+radius];}
        for(int i=0;i<kernel.length;i++)kernel[i]/=sum;
        return axis(axis(source,width,height,kernel,false),width,height,kernel,true);
    }
    private static float[] axis(float[] source,int width,int height,double[] kernel,boolean horizontal) {
        int radius=kernel.length/2;float[] out=new float[source.length];double[] row=new double[width];
        for(int y=0;y<height;y++) {
            int offset=y*width;
            for(int x=0;x<width;x++)row[x]=source[offset+x]*kernel[radius];
            for(int d=radius;d>=1;d--) {
                double weight=kernel[radius-d];
                if(!horizontal) {
                    int a=Math.max(0,y-d)*width,b=Math.min(height-1,y+d)*width;
                    for(int x=0;x<width;x++)row[x]+=(source[a+x]+(double)source[b+x])*weight;
                } else if(2*d<width) {
                    double left=source[offset],right=source[offset+width-1];
                    for(int x=0;x<d;x++)row[x]+=(left+source[offset+x+d])*weight;
                    for(int x=d;x<width-d;x++)row[x]+=(source[offset+x-d]+(double)source[offset+x+d])*weight;
                    for(int x=width-d;x<width;x++)row[x]+=(source[offset+x-d]+right)*weight;
                } else {
                    for(int x=0;x<width;x++)row[x]+=(source[offset+Math.max(0,x-d)]
                        +(double)source[offset+Math.min(width-1,x+d)])*weight;
                }
            }
            for(int x=0;x<width;x++)out[offset+x]=(float)row[x];
        }
        return out;
    }
}
