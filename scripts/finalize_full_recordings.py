"""Check and deliver the registered full-recording comparison extension."""
from __future__ import annotations
import argparse,hashlib,json,re,shutil,sys
from pathlib import Path
import tifffile
from full_recording_comparisons import ROOT,TUNE,ROUND,PREP,RENDER,BUNDLE,DELIVERY,CASES,METHODS,Tuner,read,write,sha,record
from finalize_complete_external_reviews import carrier_id
SCORE='s3_score_alignment/r27_s001_full_coverage'
FACTS='''These are complete A1 SynRCaMP fluorescence and A3 transmitted-light recordings: all 234 original images in each selected channel, at native 512 by 512 resolution. The earlier reviews contained 40-image windows. Every extracted input plane equals its original plane, and all 40 earlier window planes were matched exactly to establish channel and time identity. Only the selected channel is read by each registration method.

Every comparison has fourteen panels in the established order: unregistered original, the executed Relative-Intensity Pattern Registration Java recipe, and the same twelve external routes. The accepted numerical Java build and frozen installed-default external settings are reused, with each route newly run on the complete input. Complete-recording reference context can produce different registration from an isolated window. The panel table identifies the actual Java route and the manual declared-image-category selection source. No recipe, selector or production default was retuned or promoted.

Each full MP4 has 234 frames at 10 frames per second: 23.4 seconds. Each movement MP4 has twelve consecutive original frames around the largest step in the recorded Java transformation, plus one extra second holding each end image: sixteen encoded frames, two frames per second, eight seconds. The locator includes translation and rotation through the displacement of the image centre and four corners. It is a review locator, not ground-truth movement. Playback seconds are separate from acquisition time. No temporal interpolation or smoothing is performed.

The fourteen-panel display uses the same fixed font, layout and 1280 by 1066 canvas as the preceding collection. Every panel in a recording shares the same fixed brightness range, derived from the full source intensity histogram at quantiles 0.005 and 0.998. Native images are uncropped; display tiles are 256 pixels square. The comparison TIFF is a display-only montage. Native measurement pixels and registered native output are separately preserved in the upstream analysis stage. Java output is replayed using the frozen native warper and checked against every recorded native-plane hash. External panels use the existing common bilinear display warper on recorded transforms; no motion estimation occurs in the producer.

FAILED means that the recorded method attempt failed; any unreported execution time remains blank. Successful execution does not establish good alignment. Guide error is unavailable because no ground truth was established for these complete inputs. Zero-valued runner-schema placeholders are excluded from displayed accuracy. Timings report their actual runs, with warm-up excluded by the existing external runner; this is not a controlled speed benchmark. The two canonical-engine aliases retain their established identities and outputs.

All MP4s use H.264 with the existing quality setting, YUV 4:2:0, unchanged montage dimensions and no audio. Each movie is fully decoded and matched frame by frame to its source TIFF, with frame count, order, dimensions and playback rate checked. The TIFF and exact archival frame stacks are lossless; MP4 is a lossy viewing format.

The original 38 comparison TIFFs and 76 MP4s remain unchanged. This two-recording extension is delivered in Full_recordings. Audit contains the registered private ReproFig masters, exact panel tables, source fingerprints, producer and frame maps. Full TIFF and same-stem video archive share one identity; the movement archive has its own identity. registration_index.csv lists every current master and its source/figure-data index. No inference statistics or public derivatives were produced.
'''

def prepare_audit():
    manifests=sorted((TUNE/'s2_estimate_motion').glob('r27_e*/run.json'));assert len(manifests)==20
    def execute(_,params,dirs):
        rows=[];seen=set()
        for p in manifests:
            m=json.loads(p.read_text());assert m['status']=='done'
            info=m['outputs']['external_results'];source=TUNE/info['path'];assert sha(source)==info['sha256']
            for r in read(source):
                key=(r['series_id'],r['method_id']);assert key not in seen;seen.add(key)
                rows.append(dict(case_id=r['series_id'],method_id=r['method_id'],status=r['status'],time_seconds=r['elapsed_seconds'],settings=r['settings'],source_run=m['dir'],result_sha256=info['sha256'],guide_error_px='',accuracy_meaning='unavailable; no ground truth',native_frames=234))
        assert seen=={(c['case_id'],mid) for c in CASES for mid in METHODS}
        write(dirs.out/'coverage.csv',rows)
        return dict(outputs={'coverage':dirs.out/'coverage.csv'},summary={'recordings':2,'frames_each':234,'external_panels':24,'successful_panels':sum(r['status']=='ok' for r in rows),'failed_panels':sum(r['status']!='ok' for r in rows)})
    m=Tuner(TUNE).stage_run('s3_score_alignment',SCORE.split('/')[1],params={'estimate_manifests':{p.parent.name:sha(p) for p in manifests}},upstream=PREP,code=[Path(__file__)],fn=execute)
    record(m,'A003','Check complete-input coverage of all twelve external methods')
    results=[json.loads(p.read_text()) for p in BUNDLE.glob('der_*_video_verification.json')];assert len(results)==2
    for r in results:
        assert r['panels']==14 and r['total_frames']==234 and r['native_output_hashes_verified']==234
        for kind in ['full','movement']:assert r[kind]['decoded_all_frames']
        assert r['movement']['frames']==16 and r['movement']['unique_source_frames']==12
    (BUNDLE/'README.md').write_text('# Complete tissue recordings\n\n'+FACTS+'\nReproduce the display from its frozen sources with python plot.py --bundle .; --case selects one recording.\n',encoding='utf-8')
    print(json.dumps(m['summary'],indent=2));print('All four masters ready for structural and visual checks.')

def deliver():
    results=[json.loads(p.read_text()) for p in sorted(BUNDLE.glob('der_*_video_verification.json'))]
    assert {r['case_id'] for r in results}=={c['case_id'] for c in CASES}
    for r in results:
        for kind in ['full','movement']:assert carrier_id(BUNDLE/(r['case_id']+'_'+kind+'.reprofig'))
    DELIVERY.mkdir(exist_ok=True);audit=DELIVERY/'Audit';audit.mkdir(exist_ok=True)
    for src in BUNDLE.iterdir():
        assert src.is_file(),src
        dst=audit/src.name
        if not dst.exists() or sha(dst)!=sha(src):shutil.copy2(src,dst)
    index=[];registration=[];links=[];verified_frames=0
    expected_order=[p['method'] for p in read(ROOT/'RIPR_Java_external_comparisons/Completed_external_audit/figure_data_incucyte_vid74_a1_green_full.csv')]
    for r in results:
        cid=r['case_id'];src=BUNDLE/(cid+'_full.tif');target=DELIVERY/r['tiff']
        if target.exists():assert sha(target)==sha(src)
        else:shutil.copy2(src,target)
        r['tiff_sha256']=sha(target)
        hashes=read(BUNDLE/f'der_{cid}_frame_hashes.csv')
        with tifffile.TiffFile(target) as t:
            assert len(t.pages)==len(hashes)==234
            for p,h in zip(t.pages,hashes):
                assert p.shape==(1066,1280) and hashlib.sha256(p.asarray().tobytes()).hexdigest()==h['sha256'];verified_frames+=1
        links.append(r['tiff'])
        for kind in ['full','movement']:
            src=BUNDLE/r[kind]['file'];assert sha(src)==r[kind]['mp4_sha256']
            dest=DELIVERY/src.name
            if dest.exists():assert sha(dest)==sha(src)
            else:shutil.copy2(src,dest)
            assert sha(dest)==r[kind]['mp4_sha256'];links.append(dest.name)
            slug=cid+'_'+kind;table=audit/('figure_data_'+slug+'.csv');panels=read(table)
            assert [p['method'] for p in panels]==expected_order
            assert panels[1]['recipe_id']==r['recipe_id'] and panels[1]['selection_source']=='manual_declared_category_executed_on_complete_recording'
            archive=audit/(slug+'.reprofig');master=audit/(slug+'.tif')
            registration.append(dict(case_id=cid,version=kind,figure_id=carrier_id(archive),profile='private master',canonical_master=str(master if master.exists() else archive),video_archive=str(archive),figure_data_csv=str(table),sources_csv=str(audit/'sources.csv'),statistics_status='not_applicable',video_sha256=r[kind]['mp4_sha256']))
        index.append(dict(case_id=cid,frames=234,panels=14,recipe=r['recipe_label'],recipe_id=r['recipe_id'],tiff=r['tiff'],tiff_sha256=r['tiff_sha256'],full_mp4=r['full']['file'],full_fps=10,full_seconds=23.4,movement_mp4=r['movement']['file'],movement_fps=2,movement_seconds=8,clip_first_frame=r['clip_first_frame'],clip_last_frame=r['clip_last_frame'],external_failures=r['external_failures']))
    assert len(list(DELIVERY.glob('*.tif')))==2 and len(list(DELIVERY.glob('*.mp4')))==4
    assert len(set(links))==6 and all((DELIVERY/p).is_file() for p in links)
    assert len({r['figure_id'] for r in registration})==4 and verified_frames==468
    for r in read(TUNE/PREP/'out/previous_media_hashes.csv'):assert sha(r['path'])==r['sha256']
    write(DELIVERY/'index.csv',index);write(DELIVERY/'registration_index.csv',registration)
    page=(ROOT/'RIPR_Java_external_comparisons/WATCH_COMPARISONS.html').read_text(encoding='utf-8')
    page=re.sub(r'const recordings=.*?;const select=',lambda _: 'const recordings='+json.dumps(results).replace('</','<\\/')+';const select=',page,flags=re.S)
    page=page.replace('Registration comparison videos','Complete tissue recording comparisons').replace('href="video_index.csv"','href="index.csv"')
    (DELIVERY/'WATCH_COMPARISONS.html').write_text(page,encoding='utf-8')
    (DELIVERY/'README.md').write_text('# Complete tissue recording comparisons\n\nWATCH_COMPARISONS.html opens the slow movement clips first.\n\n'+FACTS,encoding='utf-8')
    (TUNE/'rounds'/ROUND/'review.md').write_text('# Full-recording completion\n\n'+FACTS,encoding='utf-8')
    rpath=TUNE/'rounds'/ROUND/'round.md';text=rpath.read_text(encoding='utf-8');rpath.write_text(text.replace('In progress. No accepted numerical recipe or production default is replaced.','Complete: both 234-frame recordings have the same fourteen panels, full videos and slow movement clips. All native replay pixels, display frames, movie sequences, private figure records and the 114 previous media files passed verification. No accepted numerical recipe or production default is replaced.'),encoding='utf-8')
    def record_delivery(_,params,dirs):
        checks=dict(passed=True,recordings=2,native_frames_each=234,comparison_panels=14,comparison_tiffs=2,full_mp4s=2,slow_mp4s=2,post_registration_tiff_frames_verified=468,all_video_frames_decoded=True,previous_media_hashes_preserved=114,figure_identities=4,gallery_targets_verified=6)
        (dirs.out/'delivery_verification.json').write_text(json.dumps(checks,indent=2));(DELIVERY/'completion_verification.json').write_text(json.dumps(checks,indent=2))
        return dict(outputs={'verification':dirs.out/'delivery_verification.json','index':DELIVERY/'index.csv','registration_index':DELIVERY/'registration_index.csv','figures':BUNDLE/'figures.csv'},summary=checks)
    m=Tuner(TUNE).stage_run('s4_render_review',RENDER.split('/')[1],params={'score_manifest_sha256':sha(TUNE/SCORE/'run.json'),'recordings':2,'frames_each':234},upstream=SCORE,code=[Path(__file__),ROOT/'scripts/render_completed_external_reviews.py'],fn=record_delivery)
    record(m,'A004','Deliver the registered full-recording comparison set')
    print(json.dumps(m['summary'],indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare-audit','deliver']);a=p.parse_args()
    {'prepare-audit':prepare_audit,'deliver':deliver}[a.action]()
