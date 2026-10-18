"""PyInstaller hook for PyAV.

Two things are needed for a working frozen build:

* the FFmpeg shared libraries ship in a delvewheel ``av.libs`` directory that
  lives *next to* the package, so PyInstaller has to be told about it;
* several PyAV modules are C extensions that import their siblings from C
  (``av.logging`` needs ``av._cyutil``, ...), which the module graph cannot see.
  ``collect_submodules`` pulls in every ``av.*`` module, extensions included.
"""

from PyInstaller.utils.hooks import collect_delvewheel_libs_directory, collect_submodules

datas, binaries = collect_delvewheel_libs_directory("av")
hiddenimports = collect_submodules("av")
