"""Add four complete recordings through the established frozen numerical workflow."""
from __future__ import annotations
import argparse,ctypes,json,os,shutil,sys,traceback
from pathlib import Path
import numpy as np
import tifffile
import full_recording_comparisons as base

ROOT,TUNE=base.ROOT,base.TUNE
ROUND='R28_more_full_recordings'
PREP='s1_prepare_review_inputs/r28_a001_full_inputs'
RENDER='s4_render_review/r28_r001_full_recordings'
SCORE='s3_score_alignment/r28_s001_full_coverage'
BUNDLE=TUNE/RENDER/'out/bundle'
DELIVERY=base.DELIVERY
PREVIOUS_PREP=TUNE/'s1_prepare_review_inputs/r27_a001_full_inputs/out'
CASE_MANIFEST=TUNE/'rounds/R23_further_exact_java_speed/cases.csv'
SELECTED=['synrcamp_a2','syngabasnfr_b1','per2_b2','transmitted_b3']
read,write,sha,stamp,Tuner=base.read,base.write,base.sha,base.stamp,base.Tuner

def configure():
    base.ROUND=ROUND;base.PREP=PREP;base.RENDER=RENDER;base.BUNDLE=BUNDLE
    base.JAVA_PREFIX='r28_j001_';base.EXTERNAL_PREFIX='r28_e'
    base.EXTRA_CODE=[Path(__file__)]
    base.BASELINE_LABEL='All 40 previous comparison recordings and their 120 media files unchanged'

configure()

def cases():
    previous={r['case_id']:r for r in read(CASE_MANIFEST)}
    return [dict(case_id=cid+'_whole',old_case=cid,recording=previous[cid]['recording'],
        channel=previous[cid]['channel'],image_type=previous[cid]['image_type'],
        motion=previous[cid]['motion'],role=previous[cid]['role'],
        old_input=Path(previous[cid]['input_file']).stem,
        window_start=int(previous[cid]['source_frames'].split('-')[0])) for cid in SELECTED]

def prepare():
    selected=cases();round_dir=TUNE/'rounds'/ROUND;round_dir.mkdir(exist_ok=True)
    if not (round_dir/'round.md').exists():
        (round_dir/'round.md').write_text('''# Additional complete-recording comparisons

## Jamie's words
2026-09-10: "more"

## Question
Extend the completed full-recording collection with four additional complete inputs: calcium fluorescence A2, GABA fluorescence B1, bioluminescence B2 and transmitted light B3. Each has 234 original native images. The request above continues the same fourteen-panel comparison and both movie formats.

## Diagnosis
The four source TIFFs contain 234 times, four channels and 512 by 512 uint16 pixels. Exact comparison of all forty earlier-window images identifies channels 1, 3, 4 and 2 respectively. These complete inputs have no earlier complete motion run in this collection. The existing forty reviews, including two complete tissue recordings, supply the unchanged A000 baseline. The selected historical cases comprise two controls and two failures; their roles are inherited, not accuracy claims on the new complete inputs.

## Approaches and gates
This is an authorised fixed-method extension under the request above. WORKFLOW_CONTRACT.md supplies s1_prepare_review_inputs, s2_estimate_motion, s3_score_alignment and s4_render_review. Reuse the frozen Java numerical runtime and all twelve external default routes, with one Java numerical process at a time and native internal threads one. Scientific registration stays in Java. This is not a tuning lattice or production-default promotion.
Check every extracted plane and all earlier window pixels first. Copy and verify the already compiled native replay helper and runtime fingerprints from the successful prior full-recording round. Existing adapter and replay compatibility gates are reused. Execute each method once per complete input, with an immutable checkpoint per method and recording. No crop can replace a complete run; retain genuine method failures without retuning. Java reference context may differ from forty-image windows. Preserve all 120 earlier media files and original indexes.
Render using the same producer, labels, fixed contrast, font, guide/time fields and FAILED warnings. Derive recipe labels from actual execution metadata. Verify all 936 native replay planes and delivered montage planes, all eight complete movie decodes, fourteen ordered panels per recording, eight registered identities and every gallery target. Full movies use 10 frames per second; twelve-image movement clips use 2 frames per second plus endpoint holds. Guide accuracy is unavailable. No scientific ranking or controlled timing claim is made.
One flat figure bundle holds the eight new masters. New media join Full_recordings; a separate Additional_audit and new batch indexes preserve the completed prior bundle and its immutable indexes. The gallery combines all six complete tissue recordings.

## Verdict
In progress. No numerical recipe or production default is replaced.
''',encoding='utf-8')
    originals=[TUNE.parent/'Per2_SynRCamp_SynGABASnFr_MF'/(c['recording']+'.tif') for c in selected]
    params=dict(cases=selected,originals={str(p):sha(p) for p in originals},frames=234,
        previous_runtime_manifest_sha256=sha(PREVIOUS_PREP.parent/'run.json'),case_manifest_sha256=sha(CASE_MANIFEST))
    def execute(_,params,dirs):
        rows=[]
        for c,p in zip(selected,originals):
            native=tifffile.memmap(p);assert native.shape==(234,4,512,512) and native.dtype.kind=='u' and native.dtype.itemsize==2
            old=tifffile.imread(TUNE/'inputs'/(c['old_input']+'.tif'));start=c['window_start']-1
            matches=[ch for ch in range(4) if np.array_equal(native[start:start+len(old),ch],old)]
            assert len(matches)==1 and len(old)==40
            frames=native[:,matches[0]]
            category={'DENSE_FLUORESCENCE':'DENSE_FLUOR','PHASE_CONTRAST':'PHASE_CONTRAST','SPARSE_LOW_LIGHT_FLUORESCENCE':'SPARSE_LOWLIGHT'}[c['image_type']]
            dest=dirs.out/'inputs'/category/c['case_id']/c['motion']/'NATIVE_ONLY/00_input_uncorrected.tif'
            dest.parent.mkdir(parents=True)
            tifffile.imwrite(dest,frames,imagej=True,metadata={'axes':'TYX'},photometric='minisblack',compression='deflate',compressionargs={'level':1})
            histogram=np.zeros(65536,dtype=np.int64)
            with tifffile.TiffFile(dest) as saved:
                assert len(saved.pages)==234
                for i,plane in enumerate(saved.pages):
                    assert np.array_equal(plane.asarray(),frames[i])
                    histogram+=np.bincount(frames[i].ravel(),minlength=65536)
            cumulative=np.cumsum(histogram);total=int(cumulative[-1])
            low=int(np.searchsorted(cumulative,int(.005*(total-1))+1));high=int(np.searchsorted(cumulative,int(.998*(total-1))+1));assert high>low
            layout=dirs.out/(c['case_id']+'_display.csv')
            write(layout,[dict(contrast_low=low,contrast_high=high,frames=234,contrast_source='full-recording pixel histogram, quantiles 0.005 and 0.998')])
            write(dest.parent/'truth.csv',[dict(frame=i,dx=0,dy=0,theta_radians=0) for i in range(234)])
            rows.append(dict(c,channel_one_based=matches[0]+1,source=str(dest),input_path=str(dest),sha256=sha(dest),original=str(p),original_sha256=params['originals'][str(p)],frames=234,width=512,height=512,layout=str(layout),original_pixels_exact=True,previous_window_exact=True))
            del frames,native,old
        write(dirs.out/'cases.csv',rows);write(round_dir/'cases.csv',rows)
        preserved=[];media=0
        for folder in [DELIVERY.parent,DELIVERY]:
            for p in sorted(folder.iterdir()):
                if p.is_file() and (p.suffix in ['.tif','.mp4'] or p.name.endswith('index.csv')):
                    kind='media' if p.suffix in ['.tif','.mp4'] else 'index';media+=kind=='media'
                    preserved.append(dict(path=str(p),kind=kind,sha256=sha(p),bytes=p.stat().st_size))
        assert media==120;write(dirs.out/'preserved_file_hashes.csv',preserved)
        runtime=json.loads((PREVIOUS_PREP/'java_runtime.json').read_text())
        dependencies=read(PREVIOUS_PREP/'runtime_hashes.csv')
        for r in dependencies:assert sha(r['path'])==r['sha256']
        shutil.copytree(PREVIOUS_PREP/'export_classes',dirs.out/'export_classes')
        for p in (dirs.out/'export_classes').rglob('*.class'):
            assert sha(p)==sha(PREVIOUS_PREP/'export_classes'/p.relative_to(dirs.out/'export_classes'))
            dependencies.append(dict(path=str(p),sha256=sha(p),bytes=p.stat().st_size))
        write(dirs.out/'runtime_hashes.csv',dependencies)
        (dirs.out/'java_runtime.json').write_text(json.dumps(runtime,indent=2))
        return dict(outputs={'cases':dirs.out/'cases.csv','runtime':dirs.out/'java_runtime.json','runtime_hashes':dirs.out/'runtime_hashes.csv','preserved_files':dirs.out/'preserved_file_hashes.csv'},summary=dict(recordings=4,frames_each=234,all_936_native_input_planes_exact=True,all_160_prior_window_planes_exact=True,previous_media=media))
    m=Tuner(TUNE).stage_run('s1_prepare_review_inputs',PREP.split('/')[1],params=params,code=[Path(__file__),Path(base.__file__)],fn=execute)
    base.record(m,'A000','Freeze four complete inputs and reuse all forty earlier comparisons')
    print(json.dumps(m['summary']),flush=True)

def compute():
    selected=read(TUNE/PREP/'out/cases.csv')
    for c in selected:base.java_case(c)
    base.start_bundle(selected)
    from render_completed_external_reviews import Producer
    producer=Producer(BUNDLE)
    for c in selected:
        base.external_case(c)
        producer.produce(next(j for j in producer.config['jobs'] if j['case_id']==c['case_id']))
    print('All four additional full recordings and eight videos are complete and decoded.',flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','compute']);a=p.parse_args()
    if a.action=='prepare':prepare();return
    status=ROOT/'tmp/more_full_recordings_status.json'
    def state(value,**extra):status.write_text(json.dumps(dict(state=value,pid=os.getpid(),time_utc=stamp(),**extra),indent=2))
    ctypes.windll.kernel32.SetThreadExecutionState(0x80000001);state('running')
    try:compute();state('complete')
    except BaseException as exc:state('failed',error=str(exc));print('COMPUTE FAILED: '+str(exc),flush=True);traceback.print_exc();raise
    finally:ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)

if __name__=='__main__':main()
