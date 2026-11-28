from contextlib import redirect_stdout,redirect_stderr
from io import BytesIO,StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import gzip,lzma,sys,tarfile,unittest,zipfile
import release_guard as guard
import release_zip

def tar_record(name,payload=b'content',kind=tarfile.REGTYPE):
    info=tarfile.TarInfo(name);info.type=kind;info.size=len(payload)
    return info.tobuf(format=tarfile.USTAR_FORMAT)+payload+bytes((-len(payload))%512)

def pax():
    value=b'comment=fixture\n';size=len(value)+2
    while size!=len(str(size))+1+len(value):size=len(str(size))+1+len(value)
    return str(size).encode()+b' '+value

class ArchiveBudgetCommands(unittest.TestCase):
    def setUp(self):
        folder=TemporaryDirectory();self.addCleanup(folder.cleanup);self.folder=Path(folder.name)
        self.archive=self.folder/'fixture.zip'
        with zipfile.ZipFile(self.archive,'w') as writer:
            writer.writestr('first',b'content');writer.writestr('second',b'content')
    def command(self,path,*args):
        out=StringIO()
        with patch.object(sys,'argv',['release_guard.py',str(path),*map(str,args)]),redirect_stdout(out),redirect_stderr(StringIO()):guard.main()
        return out.getvalue()
    def rejection_restores(self,path,flag,key,value,hard):
        original=getattr(guard,key)
        with self.assertRaises(ValueError):self.command(path,flag,value)
        self.assertEqual(getattr(guard,key),original)
        for invalid in (0,-1,hard+1,'bad'):
            with self.assertRaises(SystemExit):self.command(path,flag,invalid)
        self.assertEqual(getattr(guard,key),original)

    def test_zip_entry_budget_before_standard_allocation(self):
        with patch.object(guard,'MAX_ENTRIES',1):
            with patch.object(release_zip.zipfile,'ZipFile',side_effect=AssertionError('allocated ZIP metadata before checking entry cap')):
                with self.assertRaises(ValueError):guard.verify(self.archive)
        self.assertEqual(guard.verify(self.archive),(2,14))
        self.command(self.archive,'--max-entries',2)

    def test_path_metadata_budget(self):
        self.command(self.archive,'--max-path-metadata-bytes',1000)
        self.rejection_restores(self.archive,'--max-path-metadata-bytes','MAX_PATH_METADATA',1,32*1024*1024)

    def test_gzip_stream_policy(self):
        raw=tar_record('payload')+bytes(1024);path=self.folder/'fixture.tar.gz'
        path.write_bytes(gzip.compress(raw[:1024])+gzip.compress(raw[1024:]))
        self.command(path,'--max-gzip-streams',2)
        self.rejection_restores(path,'--max-gzip-streams','MAX_GZIP_STREAMS',1,4096)
        self.assertEqual(guard.verify(path),(1,7))
