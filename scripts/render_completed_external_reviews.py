"""Render complete external comparisons from immutable saved movements only.

Standalone: python plot.py --bundle . [--case CASE_ID]
Raw and accepted Java pixel inputs stay at the paths in the copied configuration.
No motion estimation or registration plugin is invoked by this producer.
"""
from __future__ import annotations
import argparse,csv,hashlib,importlib.util,io,json,os,shutil,sys,types
from datetime import datetime,timezone
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED,ZIP_STORED
import cv2
import numpy as np
import tifffile
sys.dont_write_bytecode=True

METHODS=[
 '21_real_stackreg_turboreg_translation_chain','22_real_turboreg_translation_multilag_rcc',
 '23_real_multistackreg_translation_same_engine','24_real_image_stabilizer_lucas_kanade_rolling_template',
 '25_real_fast4dreg_nanoj_previous_frame','26_real_fast4dreg_nanoj_first_frame',
 '27_real_correct_3d_drift_phase_correlation_standard','28_real_correct_3d_drift_phase_correlation_multitime',
 '29_real_linear_stack_alignment_sift_translation_chain','30_real_sift_translation_multilag_rcc',
 '31_register_virtual_stack_slices_same_sift_engine','32_real_descriptor_based_series_translation']

def read(p):
 with Path(p).open(encoding='utf-8-sig',newline='') as f:return list(csv.DictReader(f))
def write(p,rows):
 fields=list(dict.fromkeys(k for r in rows for k in r))
 with Path(p).open('w',encoding='utf-8',newline='') as f:
  w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(2**20),b''):h.update(b)
 return h.hexdigest()
def pixels(a):return hashlib.sha256(np.ascontiguousarray(a).tobytes()).hexdigest()
def norm(p):return str(p).replace('\\','/').lower()
def module(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec)
 sys.modules[name]=m;spec.loader.exec_module(m);return m

class Producer:
 def __init__(self,bundle):
  self.bundle=bundle;self.sources=read(bundle/'sources.csv')
  self.config=json.loads(next(bundle.glob('src_*complete_external_inputs.json')).read_text())
  self.delivery=Path(self.config['delivery'])
  self.style=module('completed_review_style',self.find('reproduce_registration_montage.py'))
  self.video=module('cached_video_exporter',self.find('plot.py'))
  package=types.ModuleType('frozen_warp');package.__path__=[];sys.modules['frozen_warp']=package
  self.types=module('frozen_warp.types',self.find('types.py'))
  self.core=module('frozen_warp.core',self.find('core.py'))
  self.template=read(self.find('figure_data_synrcamp_a1.csv'))
 def find(self,value):
  exact=[r for r in self.sources if norm(r['original_path'])==norm(value)]
  if not exact:exact=[r for r in self.sources if Path(r['original_path']).name==value]
  assert len(exact)==1,(value,len(exact))
  p=self.bundle/exact[0]['copied_path'];assert sha(p)==exact[0]['sha256'];return p
 def copy_source(self,path):
  path=Path(path)
  prior=[r for r in self.sources if norm(r['original_path'])==norm(path)]
  if prior:
   p=self.bundle/prior[0]['copied_path'];assert sha(p)==sha(path);return p
  name='src_'+hashlib.sha256(str(path).encode()).hexdigest()[:10]+'_'+path.name
  p=self.bundle/name;shutil.copy2(path,p)
  self.sources.append(dict(original_path=str(path),copied_path=name,file_name=path.name,
   modification_time=datetime.fromtimestamp(path.stat().st_mtime,timezone.utc).isoformat(),byte_size=p.stat().st_size,sha256=sha(p)))
  write(self.bundle/'sources.csv',self.sources);return p
 def external(self,job):
  tune=Path(self.config['tuning_root']);cid=job['case_id'];found={}
  for manifest in sorted((tune/'s2_estimate_motion').glob(self.config.get('external_run_glob','r26_e*/run.json'))):
   m=json.loads(manifest.read_text())
   if m['params']['case_id']!=cid:continue
   assert m['status']=='done' and m['params']['input_sha256']==job['source_sha256']
   self.copy_source(manifest)
   output=m['outputs']['external_results'];path=tune/output['path'];assert sha(path)==output['sha256']
   copied=self.copy_source(path)
   for r in read(copied):
    assert r['method_id'] not in found
    assert r['series_id']==cid
    found[r['method_id']]=(r,copied.name)
  assert set(found)==set(METHODS),(cid,'External results incomplete',sorted(set(METHODS)-set(found)))
  return found
 def produce(self,job):
  cid=job['case_id'];done=self.bundle/f'der_{cid}_video_verification.json'
  if done.exists():
   result=json.loads(done.read_text())
   for kind in ['full','movement']:assert sha(self.bundle/result[kind]['file'])==result[kind]['mp4_sha256']
   print(cid+': verified completed full comparison',flush=True);return result
  external=self.external(job)
  old=read(self.find(job['layout']));summary=json.loads(self.find(job['recipe_source']).read_text())
  assert job['recipe_id']==summary['route']+'__'+job['recipe_id'].split('__')[-1]
  native_hashes={r['name']:r['sha256'] for r in read(self.find(job['pixel_hashes']))}
  low=float(old[0]['contrast_low']);high=float(old[0]['contrast_high']);count=int(job['frames'])
  panels=[];trajectories={}
  for i,t in enumerate(self.template):
   p=dict(panel=i+1,row=i//5,column=i%5,method=t['method'],video=cid,n_frames=count,
    contrast_low=low,contrast_high=high,display_label=t['display_label'],status='ok',time_seconds='',guide_error_px='',
    historical_method_id='',recipe_id='',recipe_subtype='',recipe_source='',selection_source='',
    pixels_source='',time_source='',guide_source='',settings='',external_coverage='same twelve external methods on identical complete input')
   if i==0:
    p.update(selection_source='unregistered_control',pixels_source=Path(job['source']).name)
   elif i==1:
    p.update(display_label=job['recipe_label'],recipe_id=job['recipe_id'],recipe_subtype=summary['route'],
     recipe_source=self.find(job['recipe_source']).name,recipe_state='executed',selection_source=job.get('selection_source','manual_declared_category_cached_accepted_recipe'),
     time_seconds=summary['estimation_seconds'],time_source=self.find(job['recipe_source']).name,pixels_source=Path(job['registered']).name)
   else:
    mid=METHODS[i-2];r,src=external[mid]
    p.update(historical_method_id=mid,status=r['status'],time_seconds=r['elapsed_seconds'],time_source=src,
     selection_source='frozen_installed_defaults_all_defaults_native',pixels_source='common_bilinear_warp_from_'+src,settings=r['settings'])
    if r['status']=='ok':
     xyz=np.array([[float(v) for v in r[k].split(';')] for k in ['transform_x_px','transform_y_px','transform_theta_rad']]).T
     assert xyz.shape==(count,3) and np.isfinite(xyz).all()
     trajectories[i]=xyz
   panels.append(p)
  assert len(panels)==14
  base=np.zeros((1066,1280),dtype=np.uint8)
  for p in panels:
   x=256*p['column'];y=28+346*p['row'];base[y:y+90,x:x+256]=16
   for line,value in enumerate(self.style.checked_label_lines(p['display_label'])):self.style.put_text(base,value,(x+5,y+29+29*line),1.2,2)
   label='FAILED' if p['status']!='ok' else 'guide unavailable'
   if p['time_seconds']!='':label+=f" | {float(p['time_seconds']):.1f} s"
   self.style.put_text(base,label[:43],(x+5,y+82),.48)
  raw=Path(job['source']);registered=Path(job['registered']);assert sha(raw)==job['source_sha256']
  hashes=[]
  def pages():
   with tifffile.TiffFile(raw) as source,tifffile.TiffFile(registered) as native:
    assert len(source.pages)==len(native.pages)==count
    for f in range(count):
     rawframe=source.pages[f].asarray();regframe=native.pages[f].asarray()
     assert pixels(regframe.astype('<u2'))==native_hashes[f'native_{f}.pixels']
     canvas=base.copy();self.style.put_text(canvas,f"{job['title']} | image {f+1} of {count}",(7,19),.48)
     warped={}
     for i,p in enumerate(panels):
      if p['status']!='ok':continue
      if i==0:frame=rawframe
      elif i==1:frame=regframe
      else:
       xyz=tuple(trajectories[i][f]);key=xyz
       if key not in warped:warped[key]=self.core.warp_plane(rawframe,self.types.Transform(*xyz),'bilinear',0)
       frame=warped[key]
      x=256*p['column'];y=118+346*p['row'];canvas[y:y+256,x:x+256]=self.style.display_frame(frame,low,high)
     hashes.append(dict(frame=f+1,sha256=pixels(canvas)));yield canvas
     if (f+1)%25==0:print(cid+f': rendered {f+1}/{count}',flush=True)
  master=self.bundle/(cid+'_full.tif');part=master.with_suffix('.partial.tif')
  tifffile.imwrite(part,data=pages(),shape=(count,1066,1280),dtype=np.uint8,imagej=True,metadata={'axes':'TYX'},photometric='minisblack',compression='deflate',compressionargs={'level':1})
  os.replace(part,master);frames=tifffile.imread(master)
  assert frames.shape==(count,1066,1280)
  for frame,h in zip(frames,hashes):assert pixels(frame)==h['sha256']
  write(self.bundle/f'der_{cid}_frame_hashes.csv',hashes)
  # Use the same accepted-transform movement locator and fixed end pauses.
  locator=object.__new__(self.video.Producer);locator.bundle=self.bundle;locator.config=self.config;locator.source=lambda p:self.find(p)
  start,end,peak,score=locator.movement(job)
  result=dict(case_id=cid,title=job['title'],tiff=cid+'_all_methods.tif',tiff_sha256=sha(master),total_frames=count,panels=14,
   recipe_id=job['recipe_id'],recipe_label=job['recipe_label'],movement_frame_before=peak,movement_frame_after=peak+1,
   clip_first_frame=start+1,clip_last_frame=end,maximum_recorded_step_px=score,
   selection_basis='Largest RMS step across image centre and four corners from recorded accepted movements; no new registration',
   external_coverage='12 matching complete-input external methods',external_failures=sum(p['status']!='ok' for p in panels),
   native_output_hashes_verified=count,all_review_frames_readback=True)
  for kind,fps,indices in [('full',10,list(range(count))),('movement',2,[start]*2+list(range(start,end))+[end-1]*2)]:
   slug=cid+'_'+kind;selected=frames if kind=='full' else frames[indices]
   layout=[dict(p,n_frames=len(indices),source_n_frames=count,source_first_frame=indices[0]+1,source_last_frame=indices[-1]+1,
    playback_fps=fps,endpoint_extra_hold_seconds=1 if kind=='movement' else 0,acquisition_time='source image numbers; not playback seconds') for p in panels]
   write(self.bundle/f'figure_data_{slug}.csv',layout)
   mapping=[dict(video_frame=i+1,review_frame=f+1,playback_seconds=i/fps,source_pixel_sha256=pixels(frames[f])) for i,f in enumerate(indices)]
   mapfile=self.bundle/f'der_{slug}_frame_map.csv';write(mapfile,mapping)
   video=self.bundle/f'{slug}_{fps}fps.mp4';check=self.video.encode(selected,fps,video)
   check.update(file=video.name,unique_source_frames=len(set(indices)),extra_endpoint_hold_seconds=1 if kind=='movement' else 0);result[kind]=check
   preview=self.bundle/f'preview_{slug}.png';assert cv2.imwrite(str(preview),frames[peak])
   archive=self.bundle/f'{slug}.reprofig';temporary=archive.with_suffix('.partial.zip')
   with ZipFile(temporary,'w',compression=ZIP_DEFLATED,compresslevel=1,allowZip64=True) as z:
    z.write(video,video.name,compress_type=ZIP_STORED)
    if kind=='full':z.write(master,'frames.tif',compress_type=ZIP_STORED)
    else:
     stream=io.BytesIO();tifffile.imwrite(stream,selected,imagej=True,metadata={'axes':'TYX'},photometric='minisblack',compression='deflate')
     z.writestr('frames.tif',stream.getvalue(),compress_type=ZIP_STORED)
    z.write(mapfile,'frame_map.csv');z.writestr('video_verification.json',json.dumps(check,indent=2))
   os.replace(temporary,archive)
  done.write_text(json.dumps(result,indent=2));print(cid+': complete 14-panel TIFF and both fully decoded videos',flush=True);return result

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',type=Path,default=Path(__file__).parent);p.add_argument('--case');a=p.parse_args()
 cv2.setNumThreads(1);producer=Producer(a.bundle.resolve())
 for job in producer.config['jobs']:
  if not a.case or a.case==job['case_id']:producer.produce(job)
if __name__=='__main__':main()
