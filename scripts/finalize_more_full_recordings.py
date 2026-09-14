"""Audit and deliver the additional complete-recording batch without changing prior masters."""
from __future__ import annotations
import argparse,hashlib,json,re,shutil,sys
from pathlib import Path
import cv2,numpy as np,reprofig,tifffile
from more_full_recording_comparisons import ROOT,TUNE,ROUND,PREP,RENDER,SCORE,BUNDLE,DELIVERY,SELECTED,Tuner,read,write,sha,stamp,base

FACTS='''Four additional complete recordings are included: A2 calcium fluorescence (SynRCaMP), B1 GABA fluorescence (SynGABASnFR), B2 bioluminescence (Per2::Luc), and B3 transmitted light. Every selected channel contains all 234 original native 512 by 512 uint16 images. Each channel was identified by exact equality of every image in its previous forty-image window, and every extracted full-input plane was checked against the original.

Every grid uses fourteen panels in the established order: original, the actual executed Relative-Intensity Pattern Registration Java recipe, and the same twelve external routes. Numerical Java binaries, declared categories and external installed defaults are frozen from the preceding accepted workflow. The B2 runner category contains the word fluorescence for historical API compatibility; its user-facing biological channel is bioluminescence. Complete-input reference selection may differ from the earlier forty-image windows. No recipe, selector or production default was retuned or promoted.

Full MP4s contain 234 consecutive source images at 10 frames per second, lasting 23.4 seconds. Movement clips contain twelve consecutive source images around the largest recorded Java transformation step, with one additional second holding each endpoint: sixteen encoded frames at 2 frames per second, lasting eight seconds. The locator includes translation and rotation through image-centre and corner displacement. It is a review locator, not ground-truth movement. No temporal interpolation or smoothing is performed; playback time is separate from acquisition time.

The existing five-column, three-row montage uses the unchanged font, 256-pixel display tiles, 1280 by 1066 canvas and guide/time fields. All panels and frames in a recording share the fixed full-source histogram quantiles 0.005 and 0.998. Native sources are uncropped. The montage TIFFs are display-only; native measurement pixels and registered native outputs remain in the upstream analysis stages. Java output is replayed with the frozen native warper and checked against every recorded native-plane hash after ImageJ reopen. External display pixels use the established bilinear warper on the recorded transforms, without new estimation.

FAILED denotes an actual failed method attempt; unreported times remain blank. Successful execution is not proof of accurate alignment. Guide error is unavailable because these full inputs have no ground truth, and zero-valued runner-schema placeholders never enter displayed accuracy. Timings belong to the actual recorded attempts and are not a controlled speed comparison. MultiStackReg and virtual-stack feature matching retain their established canonical-engine aliases.

TIFF display stacks and exact archival frame stacks are lossless. H.264 MP4s retain the existing quality setting, YUV 4:2:0, canvas size and no audio. Every movie is fully decoded and checked frame by frame against its source TIFF for ordering, dimensions, frame count and playback rate.

This flat private master bundle contains eight figure identities, exact panel tables, source fingerprints, standalone producer and frame maps. A full TIFF and same-stem archive share one identity; its movement archive has its own. No inferential statistics, public derivatives or publication-independent accuracy claim is produced. Earlier forty comparisons and all 120 earlier media files remain unchanged.
'''

def results():
    rows=[json.loads(p.read_text()) for p in sorted(BUNDLE.glob('der_*_video_verification.json'))]
    assert {r['case_id'] for r in rows}=={c+'_whole' for c in SELECTED}
    for r in rows:
        assert r['total_frames']==234 and r['panels']==14 and r['native_output_hashes_verified']==234
        for kind,count,fps,duration in [('full',234,10,23.4),('movement',16,2,8)]:
            v=r[kind]
            assert v['decoded_all_frames'] and v['frames']==count and v['fps']==fps and v['duration_seconds']==duration
        assert r['movement']['unique_source_frames']==12
    return rows

def prepare_audit():
    manifests=sorted((TUNE/'s2_estimate_motion').glob('r28_e*/run.json'));assert len(manifests)==40
    def execute(_,params,dirs):
        coverage=[];seen=set()
        for p in manifests:
            m=json.loads(p.read_text());assert m['status']=='done'
            output=m['outputs']['external_results'];source=TUNE/output['path'];assert sha(source)==output['sha256']
            for r in read(source):
                key=(r['series_id'],r['method_id']);assert key not in seen;seen.add(key)
                coverage.append(dict(case_id=r['series_id'],method_id=r['method_id'],status=r['status'],time_seconds=r['elapsed_seconds'],settings=r['settings'],source_run=m['dir'],result_sha256=output['sha256'],guide_error_px='',accuracy_meaning='unavailable; no ground truth',native_frames=234))
        assert seen=={(c+'_whole',mid) for c in SELECTED for mid in base.METHODS}
        write(dirs.out/'coverage.csv',coverage)
        return dict(outputs={'coverage':dirs.out/'coverage.csv'},summary=dict(recordings=4,frames_each=234,external_panels=48,successful_panels=sum(r['status']=='ok' for r in coverage),failed_panels=sum(r['status']!='ok' for r in coverage)))
    m=Tuner(TUNE).stage_run('s3_score_alignment',SCORE.split('/')[1],params={'estimate_manifests':{p.parent.name:sha(p) for p in manifests}},upstream=PREP,code=[Path(__file__)],fn=execute)
    base.record(m,'A003','Check coverage of all twelve external methods on four additional complete inputs')
    rows=results()
    (BUNDLE/'README.md').write_text('# Additional complete-recording comparisons\n\n'+FACTS+'\nReproduce the display from its frozen sources with python plot.py --bundle .; --case selects one recording.\n',encoding='utf-8')
    # The producer writes previews before closing archives. Re-export from the
    # finished TIFF, verifying exact pixels, so visual-QA freshness is real.
    previews=[]
    for r in rows:
        cid=r['case_id'];index=r['movement_frame_after']-1
        with tifffile.TiffFile(BUNDLE/(cid+'_full.tif')) as t:frame=t.pages[index].asarray()
        hashes=read(BUNDLE/f'der_{cid}_frame_hashes.csv')
        assert hashlib.sha256(frame.tobytes()).hexdigest()==hashes[index]['sha256']
        for kind in ['full','movement']:
            p=BUNDLE/f'preview_{cid}_{kind}.png';assert cv2.imwrite(str(p),frame)
            assert np.array_equal(cv2.imread(str(p),cv2.IMREAD_UNCHANGED),frame)
            previews.append(dict(file=p.name,source_frame=index+1,sha256=sha(p),source_pixels_exact=True))
    write(TUNE/RENDER/'qc/final_preview_sources.csv',previews)
    print(json.dumps(m['summary'],indent=2));print('Eight masters ready for structural and visual checks.')

def gallery_rows():
    page=(DELIVERY/'WATCH_COMPARISONS.html').read_text(encoding='utf-8')
    match=re.search(r'const recordings=(.*?);const select=',page,re.S);assert match
    return page,json.loads(match.group(1))

def deliver():
    rows=results();audit=DELIVERY/'Additional_audit';audit.mkdir(exist_ok=True)
    assert json.loads((TUNE/RENDER/'qc/visual_review.json').read_text())['passed']
    for r in rows:
        for kind in ['full','movement']:
            assert reprofig.extract_record(BUNDLE/(r['case_id']+'_'+kind+'.reprofig')).figure_id
    for src in BUNDLE.iterdir():
        assert src.is_file();dst=audit/src.name
        if dst.exists():assert sha(dst)==sha(src)
        else:shutil.copy2(src,dst)
    expected=[r['method'] for r in read(DELIVERY/'Audit/figure_data_synrcamp_a1_whole_full.csv')]
    index=[];registration=[];verified_frames=0
    for r in rows:
        cid=r['case_id'];src=BUNDLE/(cid+'_full.tif');target=DELIVERY/r['tiff']
        if target.exists():assert sha(target)==sha(src)
        else:shutil.copy2(src,target)
        r['tiff_sha256']=sha(target)
        hashes=read(BUNDLE/f'der_{cid}_frame_hashes.csv')
        with tifffile.TiffFile(target) as t:
            assert len(t.pages)==len(hashes)==234
            for p,h in zip(t.pages,hashes):
                assert p.shape==(1066,1280) and hashlib.sha256(p.asarray().tobytes()).hexdigest()==h['sha256'];verified_frames+=1
        for kind in ['full','movement']:
            src=BUNDLE/r[kind]['file'];assert sha(src)==r[kind]['mp4_sha256'];dst=DELIVERY/src.name
            if dst.exists():assert sha(dst)==sha(src)
            else:shutil.copy2(src,dst)
            slug=cid+'_'+kind;table=audit/('figure_data_'+slug+'.csv');panels=read(table)
            assert [p['method'] for p in panels]==expected
            assert panels[1]['recipe_id']==r['recipe_id'] and panels[1]['selection_source']=='manual_declared_category_executed_on_complete_recording'
            archive=audit/(slug+'.reprofig');master=audit/(slug+'.tif');master=master if master.exists() else archive
            fid=reprofig.extract_record(archive).figure_id;assert reprofig.extract_record(master).figure_id==fid
            registration.append(dict(case_id=cid,version=kind,figure_id=fid,profile='private master',canonical_master=str(master),video_archive=str(archive),figure_data_csv=str(table),sources_csv=str(audit/'sources.csv'),statistics_status='not_applicable',video_sha256=r[kind]['mp4_sha256']))
        index.append(dict(case_id=cid,frames=234,panels=14,recipe=r['recipe_label'],recipe_id=r['recipe_id'],tiff=r['tiff'],tiff_sha256=r['tiff_sha256'],full_mp4=r['full']['file'],full_fps=10,full_seconds=23.4,movement_mp4=r['movement']['file'],movement_fps=2,movement_seconds=8,clip_first_frame=r['clip_first_frame'],clip_last_frame=r['clip_last_frame'],external_failures=r['external_failures']))
    assert verified_frames==936 and len({r['figure_id'] for r in registration})==8
    for r in read(TUNE/PREP/'out/preserved_file_hashes.csv'):assert sha(r['path'])==r['sha256']
    write(DELIVERY/'additional_recordings_index.csv',index);write(DELIVERY/'additional_registration_index.csv',registration)
    combined_index=read(DELIVERY/'index.csv')+index;combined_registration=read(DELIVERY/'registration_index.csv')+registration
    assert len(combined_index)==6 and len({r['case_id'] for r in combined_index})==6
    assert len(combined_registration)==12 and len({r['figure_id'] for r in combined_registration})==12
    write(DELIVERY/'all_recordings_index.csv',combined_index);write(DELIVERY/'all_registration_index.csv',combined_registration)
    page,earlier=gallery_rows();new_ids={r['case_id'] for r in rows};combined=[r for r in earlier if r['case_id'] not in new_ids]+rows
    assert len(combined)==6
    page=re.sub(r'const recordings=.*?;const select=',lambda _:'const recordings='+json.dumps(combined).replace('</','<\\/')+';const select=',page,flags=re.S)
    page=page.replace('href="index.csv"','href="all_recordings_index.csv"')
    (DELIVERY/'WATCH_COMPARISONS.html').write_text(page,encoding='utf-8')
    failures=[r for r in read(TUNE/SCORE/'out/coverage.csv') if r['status']!='ok']
    outcomes='\nRecorded failures in this batch:\n'+(''.join('- '+r['case_id']+': '+r['method_id']+'; '+r['status']+'\n' for r in failures) if failures else 'None. All external panels completed.\n')
    (DELIVERY/'README.md').write_text('# Six complete tissue recording comparisons\n\nWATCH_COMPARISONS.html opens the movement clips; the Full review button plays every image.\n\nThe earlier A1 calcium fluorescence and A3 transmitted-light recordings are joined by four additional complete recordings, all with 234 images and fourteen panels. all_recordings_index.csv lists all six and all_registration_index.csv lists their twelve figure identities. Audit and the original indexes preserve the first two; Additional_audit and the additional indexes hold the four additions.\n\n'+FACTS+outcomes,encoding='utf-8')
    (TUNE/'rounds'/ROUND/'review.md').write_text('# Additional complete-recording delivery\n\n'+FACTS+outcomes,encoding='utf-8')
    def record_delivery(_,params,dirs):
        checks=dict(passed=True,new_recordings=4,total_full_recordings=6,native_frames_each=234,panels_each=14,new_tiffs=4,new_full_mp4s=4,new_slow_mp4s=4,post_registration_tiff_planes_verified=verified_frames,new_figure_identities=8,all_movie_frames_decoded=True,previous_media_unchanged=120,external_failure_panels=len(failures))
        (dirs.out/'delivery_verification.json').write_text(json.dumps(checks,indent=2))
        (DELIVERY/'additional_completion_verification.json').write_text(json.dumps(checks,indent=2))
        return dict(outputs={'verification':dirs.out/'delivery_verification.json','index':DELIVERY/'additional_recordings_index.csv','registration_index':DELIVERY/'additional_registration_index.csv','figures':BUNDLE/'figures.csv'},summary=checks)
    m=Tuner(TUNE).stage_run('s4_render_review',RENDER.split('/')[1],params={'score_manifest_sha256':sha(TUNE/SCORE/'run.json'),'recordings':4,'frames_each':234},upstream=SCORE,code=[Path(__file__),ROOT/'scripts/render_completed_external_reviews.py'],fn=record_delivery)
    base.record(m,'A004','Deliver four additional registered complete-recording comparisons')
    print(json.dumps(m['summary'],indent=2))

def verify():
    class ScopedPath:
        def __init__(self,path):self.path=path
        def glob(self,pattern):
            if self.path==TUNE:
                assert pattern=='s*/*/run.json'
                return [p for prefix in ['r26','r27','r28'] for p in self.path.glob('s*/'+prefix+'_*/run.json')]
            assert self.path==TUNE/'rounds' and pattern=='*'
            return [self.path/name for name in ['R26_complete_external_comparisons','R27_full_recording_comparisons',ROUND]]
        def __truediv__(self,value):
            path=self.path/value;return ScopedPath(path) if path==TUNE/'rounds' else path
    tuner=Tuner(TUNE);tuner.root=ScopedPath(TUNE);problems=tuner.verify()
    counts={p:len(list(TUNE.glob('s*/'+p+'_*/run.json'))) for p in ['r26','r27','r28']}
    assert counts=={'r26':34,'r27':25,'r28':47} and not problems,problems
    _,rows=gallery_rows();assert len(rows)==6;targets=[]
    for r in rows:
        assert r['total_frames']==234 and r['panels']==14
        assert sha(DELIVERY/r['tiff'])==r['tiff_sha256'];targets.append(r['tiff'])
        for kind in ['full','movement']:
            assert sha(DELIVERY/r[kind]['file'])==r[kind]['mp4_sha256'] and r[kind]['decoded_all_frames'];targets.append(r[kind]['file'])
    assert len(set(targets))==18 and len(list(DELIVERY.glob('*.tif')))==6 and len(list(DELIVERY.glob('*.mp4')))==12
    registered=read(DELIVERY/'all_registration_index.csv');assert len(registered)==12
    for r in registered:
        for key in ['canonical_master','video_archive','figure_data_csv','sources_csv']:assert Path(r[key]).is_file()
        assert reprofig.extract_record(r['canonical_master']).figure_id==r['figure_id']
        assert reprofig.extract_record(r['video_archive']).figure_id==r['figure_id']
    for r in read(TUNE/PREP/'out/preserved_file_hashes.csv'):assert sha(r['path'])==r['sha256']
    for p in BUNDLE.iterdir():assert p.is_file() and sha(p)==sha(DELIVERY/'Additional_audit'/p.name)
    sources=read(DELIVERY/'Additional_audit/sources.csv')
    for r in sources:assert sha(DELIVERY/'Additional_audit'/r['copied_path'])==r['sha256']
    report=dict(passed=True,standard_tuner_verify='Read-only scope adapter; older incompatible legacy output-list schemas remain unmodified',run_counts=counts,total_stage_manifests=106,stage_problems=problems,full_recordings=6,media_targets=18,figure_identities=12,new_source_copies_verified=len(sources),previous_media_unchanged=120,registered_audit_copy_exact=True)
    (TUNE/RENDER/'qc/final_verification.json').write_text(json.dumps(report,indent=2))
    (DELIVERY/'all_recordings_verification.json').write_text(json.dumps(report,indent=2))
    p=TUNE/'rounds'/ROUND/'round.md';text=p.read_text(encoding='utf-8');p.write_text(text.replace('In progress. No numerical recipe or production default is replaced.','Complete: four additional 234-frame recordings, eight registered masters and all movie versions delivered. All 106 current and preceding stage manifests, eighteen gallery targets and prior media preservation checks passed. No numerical recipe or production default is replaced.'),encoding='utf-8')
    if not any(r['attempt_id']=='A005' for r in Tuner(TUNE).attempts(ROUND)):
        m=json.loads((TUNE/RENDER/'run.json').read_text())
        Tuner(TUNE).append_attempt(ROUND,dict(attempt_id='A005',date=stamp(),approaches='Close verified registered delivery',stage_runs=RENDER,outputs_sha256=m['outputs_sha256'],primary='Six complete recordings available with identical fourteen-panel coverage',controls='All 120 prior media files and prior indexes unchanged',guardrails='106 stage manifests; eighteen media links; twelve figure identities verified',review_status='complete; registered; all checks passed',decision='Delivered; no numerical recipe promotion',notes='Final verification receipt in render-stage qc'))
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare-audit','deliver','verify']);a=p.parse_args()
    {'prepare-audit':prepare_audit,'deliver':deliver,'verify':verify}[a.action]()
