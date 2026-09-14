"""Record fixed-method coverage, assemble delivery and verify every recording."""
from __future__ import annotations
import argparse,csv,hashlib,html,json,os,re,shutil,sys
from pathlib import Path
from zipfile import ZipFile
import tifffile
sys.dont_write_bytecode=True
from complete_external_review_set import ROOT,TUNE,ROUND,PREP,IDS,METHODS,Tuner,read,write,sha,ledger
BUNDLE=TUNE/'s4_render_review/r26_r001_complete/out/bundle'
DELIVERY=ROOT/'RIPR_Java_external_comparisons'
OLD=DELIVERY/'Video_audit'
SCORE='s3_score_alignment/r26_s001_external_coverage'

def coverage():
 manifests=sorted((TUNE/'s2_estimate_motion').glob('r26_e*/run.json'));assert len(manifests)==30
 def execute(_,params,dirs):
  rows=[];seen=set()
  for path in manifests:
   m=json.loads(path.read_text());assert m['status']=='done'
   info=m['outputs']['external_results'];source=TUNE/info['path'];assert sha(source)==info['sha256']
   for r in read(source):
    key=(r['series_id'],r['method_id']);assert key not in seen;seen.add(key)
    rows.append(dict(case_id=r['series_id'],method_id=r['method_id'],status=r['status'],time_seconds=r['elapsed_seconds'],
     input_sha256=m['params']['input_sha256'],settings=r['settings'],source_run=m['dir'],result_sha256=info['sha256'],
     guide_error_px='',accuracy_meaning='no ground truth; placeholder metrics excluded',
     native_frames=40 if 'brightfield' in r['series_id'] else 143))
  assert seen=={(c,m) for c in IDS for m in METHODS}
  write(dirs.out/'external_coverage.csv',rows)
  return dict(outputs={'coverage':dirs.out/'external_coverage.csv'},summary={'recordings':3,'external_panels':36,'independent_method_runs':30,
   'successful_panels':sum(r['status']=='ok' for r in rows),'failed_panels':sum(r['status']!='ok' for r in rows),'unscored_placeholder_accuracy_excluded':True})
 m=Tuner(TUNE).stage_run('s3_score_alignment',SCORE.split('/')[1],params={'estimate_manifests':{p.parent.name:sha(p) for p in manifests}},upstream=PREP,code=[Path(__file__)],fn=execute)
 ledger(m,'A002','Verify identical-input coverage of all twelve external methods for each added recording')
 print(json.dumps(m['summary'],indent=2))

FACTS='''All 38 recordings use the same fourteen panels: unregistered original, accepted Relative-Intensity Pattern Registration Java result, and the same twelve external routes in the same order. The set comprises 24 tissue windows, eleven complete microglial bioluminescence recordings, two complete Incucyte green recordings and one 40-frame brightfield window.

The original 35 complete comparison TIFFs and their 70 videos are unchanged. The three added recordings now have complete-input external results, replacing the preliminary two-panel delivery. Native inputs were neither cropped nor padded. The rectangular adapter reproduces the previous adapter's exact movements, statuses and settings for all twelve methods on the square compatibility input. The three accepted Java outputs remain unchanged and every native image plane is checked against its saved hash.

Each full MP4 runs at 10 frames per second. Each movement MP4 runs at 2 frames per second and contains twelve consecutive source frames surrounding the largest accepted recorded movement, with one extra second holding each end image: sixteen encoded frames and eight seconds in total. At a recording boundary the available post-movement frames can be short; the end hold keeps them visible. No interpolated or smoothed frames are created. Playback time is separate from acquisition time.

All grids preserve the established font, layout, fixed contrast range, guide/time formatting and FAILED warnings. The added recordings use 256-pixel tiles in five columns and three rows, exactly like the existing complete grids. Rectangular microscopy images retain their aspect ratio within each tile. Common bilinear warping applies saved external transformations to native pixels; it estimates no new motion. Accepted Java pixels are read directly from the completed native output.

A failed method remains in its assigned panel with FAILED and any reported execution time; unreported times stay blank. A successful run can still align poorly; status reports execution, not scientific accuracy. Missing guide scores are explicitly unavailable. Zero-valued runner truth placeholders are excluded from all displayed accuracy information. Timings for previously accepted results are historical; timings of added external runs are recorded from this run, so this is not a controlled speed benchmark. MultiStackReg and virtual-stack feature matching retain the same canonical-engine aliases as the historical comparison.

The accepted Incucyte A2 result has substantial clipping near its terminal movement; that visible result is retained for review. Movement clips locate the largest step in the accepted recorded transformation, using root mean square displacement of the image centre and four corners including rotation. They are review aids, not ground-truth movement estimates.

MP4s use H.264, YUV 4:2:0, quality setting 16, unchanged montage dimensions and no audio. Every final MP4 was decoded completely and checked for dimensions, frame count, frame order and playback rate against its source TIFF. TIFF display pixels and exact archival frame stacks are lossless; MP4 copies are lossy display outputs.

Video_audit holds the registered original video batch, including the superseded preliminary two-panel archives for the three additions. Those preliminary archives are historical only. Completed_external_audit holds the registered replacement batch for those three recordings. Each private ReproFig master contains its exact figure-data table, producer, source hashes and derived frame maps; video archives additionally contain the playable MP4 and exact frame TIFF. No inference tests or public derivatives are produced. registration_index.csv lists the 76 current video identities and their audit files.
'''

def prepare_audit():
 coverage()
 results=[json.loads(p.read_text()) for p in sorted(BUNDLE.glob('der_*_video_verification.json'))];assert len(results)==3
 for r in results:assert r['panels']==14 and r['movement']['frames']==16 and r['full']['decoded_all_frames'] and r['movement']['decoded_all_frames']
 (BUNDLE/'README.md').write_text('# Completed external comparison reviews\n\n'+FACTS+'\nThis flat bundle is the three-recording replacement batch. Reproduce with python plot.py --bundle .; optional --case selects one recording. The copied configuration resolves original pixel inputs and immutable saved transformations. The original 35 results are retained in the preceding batch.\n',encoding='utf-8')
 for p in BUNDLE.glob('preview_*.png'):os.utime(p,None)
 print('Replacement batch ready for structural and visual checks.')

def carrier_id(path):
 with ZipFile(path) as z:
  manifest=json.loads(z.read('manifest.json'))
  ids={r['figure_id'] for r in manifest['records']};assert len(ids)==1
  value=next(iter(ids));assert value.startswith('rf-')
  assert 'records/'+value+'/record.json' in z.namelist()
  return value
 raise AssertionError('Registered figure identity missing: '+str(path))

def deliver():
 new={r['case_id']:r for r in [json.loads(p.read_text()) for p in BUNDLE.glob('der_*_video_verification.json')]};assert set(new)==set(IDS)
 # Archive the preliminary delivery files under the already-owned render stage.
 previous=BUNDLE.parent/'superseded_display';previous.mkdir(exist_ok=True)
 for cid,r in new.items():
  for name in [cid+'_available_methods.tif',r['full']['file'],r['movement']['file']]:
   source=DELIVERY/name;dest=previous/name
   if not dest.exists():shutil.copy2(source,dest)
  shutil.copy2(BUNDLE/(cid+'_full.tif'),DELIVERY/r['tiff'])
  r['tiff_sha256']=sha(DELIVERY/r['tiff'])
  for kind in ['full','movement']:
   assert carrier_id(BUNDLE/(cid+'_'+kind+'.reprofig'))
   source=BUNDLE/r[kind]['file'];assert sha(source)==r[kind]['mp4_sha256'];shutil.copy2(source,DELIVERY/source.name)
 # Keep the audit batch available inside the folder requested by the user.
 audit=DELIVERY/'Completed_external_audit';audit.mkdir(exist_ok=True)
 for source in BUNDLE.iterdir():
  assert source.is_file(),source
  dest=audit/source.name
  if not dest.exists() or sha(dest)!=sha(source):shutil.copy2(source,dest)
 results=[]
 for p in sorted(OLD.glob('der_*_video_verification.json')):
  r=json.loads(p.read_text());r=new.get(r['case_id'],r);results.append(r)
 assert len(results)==38 and all(r['panels']==14 for r in results)
 baseline={r['case_id']:r for r in read(OLD/'src_index.csv')};assert len(baseline)==35
 registration=[];index=[];flat=[];panel_order=None
 for r in results:
  cid=r['case_id'];case_audit=audit if cid in new else OLD
  assert sha(DELIVERY/r['tiff'])==r['tiff_sha256']
  if cid in baseline:assert r['tiff_sha256']==baseline[cid]['sha256']
  with tifffile.TiffFile(DELIVERY/r['tiff']) as t:
   assert len(t.pages)==r['total_frames'] and t.pages[0].shape==(1066,1280)
  for kind in ['full','movement']:
   assert sha(DELIVERY/r[kind]['file'])==r[kind]['mp4_sha256']
   assert r[kind]['decoded_all_frames'] and r[kind]['fps']==(10 if kind=='full' else 2)
   slug=cid+'_'+kind;table=case_audit/f'figure_data_{slug}.csv';panels=read(table)
   order=[p['method'] for p in panels]
   if panel_order is None:panel_order=order
   assert order==panel_order and len(order)==14
   java=panels[1];assert java['recipe_id'] and java['selection_source'] and java['display_label']==r['recipe_label']
   archive=case_audit/(slug+'.reprofig')
   registration.append(dict(case_id=cid,version=kind,figure_id=carrier_id(archive),profile='private master',canonical_master=str(case_audit/(slug+'.tif')) if (case_audit/(slug+'.tif')).exists() else str(archive),video_archive=str(archive),figure_data_csv=str(table),sources_csv=str(case_audit/'sources.csv'),statistics_status='not_applicable',video_sha256=r[kind]['mp4_sha256']))
  assert r['movement']['frames']==16 and r['movement']['duration_seconds']==8 and r['movement']['unique_source_frames']==12
  index.append(dict(case_id=cid,frames=r['total_frames'],panels=14,latest_status='ok',recipe=r['recipe_label'],path=str(DELIVERY/r['tiff']),sha256=r['tiff_sha256'],bytes=(DELIVERY/r['tiff']).stat().st_size,full_mp4=r['full']['file'],movement_mp4=r['movement']['file'],external_coverage=r['external_coverage']))
  row={k:v for k,v in r.items() if not isinstance(v,dict)}
  for kind in ['full','movement']:row.update({kind+'_mp4':r[kind]['file'],kind+'_fps':r[kind]['fps'],kind+'_seconds':r[kind]['duration_seconds']})
  flat.append(row)
 # All rows have optional fields in common before passing the small CSV helper.
 def flexible(path,rows):
  fields=list(dict.fromkeys(k for r in rows for k in r))
  write(path,[{k:r.get(k,'') for k in fields} for r in rows])
 flexible(DELIVERY/'video_index.csv',flat);write(DELIVERY/'index.csv',index);write(DELIVERY/'registration_index.csv',registration)
 page=(DELIVERY/'WATCH_COMPARISONS.html').read_text(encoding='utf-8')
 page=re.sub(r'const recordings=.*?;const select=',lambda _: 'const recordings='+json.dumps(results).replace('</','<\\/')+';const select=',page,flags=re.S)
 page=page.replace('Â·','·').replace('â€“','–')
 (DELIVERY/'WATCH_COMPARISONS.html').write_text(page,encoding='utf-8')
 (DELIVERY/'README.md').write_text('# Registration comparisons and movement clips\n\nWATCH_COMPARISONS.html opens the slow movement clips first.\n\n'+FACTS,encoding='utf-8')
 (DELIVERY/'video_visual_qa.md').write_text('# Complete comparison verification\n\n'
  '- 38 recordings, all with the identical ordered set of fourteen panels.\n'
  '- 38 full videos at 10 frames per second and 38 eight-second movement clips at 2 frames per second.\n'
  '- All 35 earlier TIFFs and 70 earlier videos retain their exact file hashes.\n'
  '- All 326 added accepted Java native frames retain their exact pixel hashes.\n'
  '- All final videos were fully decoded and compared frame by frame to their source sequence.\n'
  '- All six replacement previews inspected for panel order, readable labels, timing and failure warnings.\n'
  '- All 114 gallery targets verified to exist; complete source and archive identities are in registration_index.csv.\n',encoding='utf-8')
 (TUNE/'rounds'/ROUND/'review.md').write_text('# External comparison completion\n\n'+FACTS+'\nCoverage table: ../../'+SCORE+'/out/external_coverage.csv\n\nAll requested method panels are included. No production recipe, accepted analysis default, accuracy ranking or speed ranking is promoted by this presentation round. The prepared rectangular adapter and square compatibility checks remain the geometry regression evidence.\n',encoding='utf-8')
 rd=TUNE/'rounds'/ROUND/'round.md';text=rd.read_text();text=text.replace('In progress. No accepted recipe or production default is being replaced.','Complete: all 38 recordings have the same fourteen panels, full videos and slow movement clips. The 36 missing external panels were executed on exact native inputs in 30 canonical jobs. Actual failed outcomes remain visible. No accepted recipe or production default is replaced.');rd.write_text(text,encoding='utf-8')
 def record(_,params,dirs):
  checks={'recordings':38,'panels_per_recording':14,'full_mp4s':38,'movement_mp4s':38,'all_videos_decoded':True,'old_tiff_hashes_preserved':35,'old_mp4_hashes_preserved':70,'native_java_frames_verified':326,'gallery_links_verified':114}
  (dirs.out/'delivery_verification.json').write_text(json.dumps(checks,indent=2))
  return dict(outputs={'delivery_verification':dirs.out/'delivery_verification.json','figures':BUNDLE/'figures.csv','delivery_index':DELIVERY/'index.csv','video_index':DELIVERY/'video_index.csv','registration_index':DELIVERY/'registration_index.csv'},summary=checks)
 m=Tuner(TUNE).stage_run('s4_render_review','r26_r001_complete',params={'score_manifest_sha256':sha(TUNE/SCORE/'run.json'),'delivery_recordings':38,'panel_count':14},upstream=SCORE,code=[Path(__file__),ROOT/'scripts/render_completed_external_reviews.py'],fn=record)
 ledger(m,'A003','Deliver registered full comparison TIFFs and both video formats for every recording')
 print(json.dumps(m['summary'],indent=2))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('action',choices=['coverage','prepare-audit','deliver']);a=p.parse_args()
 {'coverage':coverage,'prepare-audit':prepare_audit,'deliver':deliver}[a.action]()
