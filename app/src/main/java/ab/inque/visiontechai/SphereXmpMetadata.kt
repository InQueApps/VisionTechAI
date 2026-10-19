package ab.inque.visiontechai

/**
 * Google Photo Sphere XMP metadata for equirectangular JPEG export.
 */
object SphereXmpMetadata {

    fun buildGpanoXmp(width: Int, height: Int): String = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
    GPano:UsePanoramaViewer="True"
    GPano:ProjectionType="equirectangular"
    GPano:FullPanoWidthPixels="$width"
    GPano:FullPanoHeightPixels="$height"
    GPano:CroppedAreaLeftPixels="0"
    GPano:CroppedAreaTopPixels="0"
    GPano:CroppedAreaImageWidthPixels="$width"
    GPano:CroppedAreaImageHeightPixels="$height"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
}
