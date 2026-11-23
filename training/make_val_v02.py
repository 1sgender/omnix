"""Валидация для train_fpfix.py (пути в начале жёстко заданы под локальную раскладку, правятся при запуске).
mkhn: python make_val_v02.py hn <raw_hn> <hn_features.npy> 6
mkval: python make_val_v02.py stream <dir с raw_hn>  -> <dir>/val_stream.npy
"""
import sys
mode=sys.argv.pop(1)
if mode=="hn":
    import sys, random, numpy as np
    from pathlib import Path
    sys.path.insert(0,"C:/Users/User/Desktop/claude edit/omnix-wake-v02/training")
    import make_features as mf
    from openwakeword.utils import AudioFeatures
    W=Path("C:/Users/User/Desktop/claude edit/omnix-voice-work/wakeword-real")
    apk=Path("C:/Users/User/Desktop/claude edit/omnix-wake-v02/app/src/main/assets/wakeword")
    F=AudioFeatures(melspec_model_path=str(apk/"melspectrogram.onnx"),embedding_model_path=str(apk/"embedding_model.onnx"),ncpu=2)
    bgs=sorted((W/"features_v2/bg").glob("*.wav")); print(len(bgs))
    paths=sorted(Path(sys.argv[1]).glob("train/negative/*.wav")); print(len(paths))
    rng=random.Random(4242); random.seed(4242); np.random.seed(4242)
    f=mf.aug_and_embed(paths,int(sys.argv[3]),F,bgs,rng,pos_snr=None)
    np.save(sys.argv[2],f); print(f.shape)
else:
    import sys, random, numpy as np
    from pathlib import Path
    sys.path.insert(0,"C:/Users/User/Desktop/claude edit/omnix-wake-v02/training")
    import make_features as mf
    from openwakeword.utils import AudioFeatures
    W=Path("C:/Users/User/Desktop/claude edit/omnix-voice-work/wakeword-real"); S=Path(sys.argv[1])
    apk=Path("C:/Users/User/Desktop/claude edit/omnix-wake-v02/app/src/main/assets/wakeword")
    F=AudioFeatures(melspec_model_path=str(apk/"melspectrogram.onnx"),embedding_model_path=str(apk/"embedding_model.onnx"),ncpu=2)
    mf.RAW=S/"raw_hn"; mf.BG=W/"features_v2/bg"
    random.seed(1);np.random.seed(1)
    st=mf.make_negative_segments(random.Random(778),30)
    v=mf.stream_to_windows(F,st).reshape(-1,96); np.save(S/"val_stream.npy",v); print(v.shape, len(v)/12.5/3600)
